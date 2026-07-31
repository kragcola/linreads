import http from '@ohos.net.http'
import convertxml from '@ohos.convertxml'

export interface BookMeta {
  id: number
  title: string[]
  authors: string[]
  formats: string[]
  tags: string[]
  series: string | null
  series_index: number | null
  libraryId: string
  cover: string
  last_modified: string
}

interface CalibreEntry extends BookMeta {
  acquisitions: Map<string, string>
}

interface OpdsFeed {
  publications: CalibreEntry[]
  navigation: NavigationEntry[]
  searchTemplate: string | null
  nextUrl: string | null
}

interface NavigationEntry {
  title: string | null
  url: string
}

interface FeedState {
  entries: CalibreEntry[]
  nextUrl: string | null
  serverSearch: boolean
  visitedUrls: Set<string>
}

interface Acquisition {
  url: string
  type: string
  formatHint: string | null
}

interface XmlNode {
  _type?: string
  _name?: string
  _text?: string
  _attributes?: Record<string, string>
  _elements?: XmlNode[]
}

// Configured via Settings; defaults to local dev
let BASE_URL = 'http://192.168.1.1:8080'
let LIBRARY_ID = 'calibre-library'
const MAX_NAVIGATION_DEPTH = 4
const MAX_FEEDS_PER_SESSION = 256
const SEARCH_TERMS_SENTINEL = 'READFLOW_SEARCH_TERMS'
const SEARCH_TERMS_OPTIONAL_SENTINEL = 'READFLOW_SEARCH_TERMS_OPTIONAL'

let rootFeedPromise: Promise<OpdsFeed> | null = null
let browseStatePromise: Promise<FeedState> | null = null
const searchStates: Map<string, FeedState> = new Map()
const cachedById: Map<number, CalibreEntry> = new Map()

export function setCalibreBaseUrl(url: string) {
  BASE_URL = normalizeBaseUrl(url)
  rootFeedPromise = null
  browseStatePromise = null
  searchStates.clear()
  cachedById.clear()
}

export function setCalibreLibraryId(libraryId: string) {
  LIBRARY_ID = libraryId.trim() || 'calibre-library'
  rootFeedPromise = null
  browseStatePromise = null
  searchStates.clear()
  cachedById.clear()
}

export const CalibreService = {
  async search(query = '', num = 100): Promise<BookMeta[]> {
    const state = await stateFor(query.trim())
    const requested = Math.max(1, num)
    const matches: CalibreEntry[] = []
    let scanIndex = 0

    while (matches.length < requested) {
      while (scanIndex >= state.entries.length && state.nextUrl !== null) {
        await appendNextPage(state)
      }
      if (scanIndex >= state.entries.length) break
      const entry = state.entries[scanIndex]
      scanIndex += 1
      if (state.serverSearch || query.trim().length === 0 || entryMatchesQuery(entry, query)) {
        cachedById.set(entry.id, entry)
        matches.push(entry)
      }
    }

    return matches.map(stripRuntimeFields)
  },

  downloadUrl(id: number, format: string, libraryId: string = LIBRARY_ID): string {
    const normalizedFormat = format.toLowerCase()
    const fromFeed = cachedById.get(id)?.acquisitions.get(normalizedFormat)
    if (fromFeed !== undefined) return fromFeed
    return resolveHref(
      `/get/${encodeURIComponent(normalizedFormat)}/${id}/${encodeURIComponent(libraryId)}`,
      BASE_URL,
    )
  },
}

function stripRuntimeFields(entry: CalibreEntry): BookMeta {
  return {
    id: entry.id,
    title: entry.title,
    authors: entry.authors,
    formats: entry.formats,
    tags: entry.tags,
    series: entry.series,
    series_index: entry.series_index,
    libraryId: entry.libraryId,
    cover: entry.cover,
    last_modified: entry.last_modified,
  }
}

async function stateFor(query: string): Promise<FeedState> {
  if (query.length === 0) return initialBrowseState()
  const existing = searchStates.get(query)
  if (existing !== undefined) return existing
  const root = await rootFeed()
  let state: FeedState
  if (root.searchTemplate !== null) {
    const searchUrl = expandSearchTemplate(root.searchTemplate, query)
    state = feedState(await fetchFeed(searchUrl), true, searchUrl)
  } else {
    state = copyForLocalSearch(await initialBrowseState())
  }
  searchStates.set(query, state)
  return state
}

async function initialBrowseState(): Promise<FeedState> {
  if (browseStatePromise !== null) return browseStatePromise
  browseStatePromise = (async (): Promise<FeedState> => {
    const root = await rootFeed()
    if (root.publications.length > 0 || root.navigation.length === 0) {
      return feedState(root, false, opdsRootUrl())
    }

    let navigation = root.navigation
    const visited = new Set<string>()
    visited.add(opdsRootUrl())
    for (let depth = 0; depth < MAX_NAVIGATION_DEPTH; depth += 1) {
      const target = navigation.find((entry) => isCalibreTitleNavigation(entry.url)) ?? navigation[0]
      if (target === undefined || visited.has(target.url)) throw new Error('OPDS 导航形成循环')
      visited.add(target.url)
      const feed = await fetchFeed(target.url)
      if (feed.publications.length > 0 || feed.navigation.length === 0) {
        return feedState(feed, false, target.url, visited)
      }
      navigation = feed.navigation
    }
    throw new Error('OPDS 导航层级过深')
  })()
  return browseStatePromise
}

async function rootFeed(): Promise<OpdsFeed> {
  if (rootFeedPromise === null) rootFeedPromise = fetchFeed(opdsRootUrl())
  return rootFeedPromise
}

function feedState(feed: OpdsFeed, serverSearch: boolean, initialUrl: string, visitedUrls?: Set<string>): FeedState {
  feed.publications.forEach((entry) => cachedById.set(entry.id, entry))
  const visited = visitedUrls ?? new Set<string>()
  visited.add(initialUrl)
  return {
    entries: [...feed.publications],
    nextUrl: feed.nextUrl,
    serverSearch,
    visitedUrls: visited,
  }
}

function copyForLocalSearch(state: FeedState): FeedState {
  return {
    entries: [...state.entries],
    nextUrl: state.nextUrl,
    serverSearch: false,
    visitedUrls: new Set(state.visitedUrls),
  }
}

async function appendNextPage(state: FeedState): Promise<void> {
  const next = state.nextUrl
  if (next === null) return
  if (state.visitedUrls.has(next)) throw new Error('OPDS 分页形成循环')
  if (state.visitedUrls.size >= MAX_FEEDS_PER_SESSION) throw new Error('OPDS 分页数量超过安全上限')
  const feed = await fetchFeed(next)
  state.visitedUrls.add(next)
  feed.publications.forEach((entry) => cachedById.set(entry.id, entry))
  state.entries.push(...feed.publications)
  state.nextUrl = feed.nextUrl
}

async function fetchFeed(url: string): Promise<OpdsFeed> {
  const xml = await getText(url)
  return parseOpdsFeed(xml, url)
}

async function getText(url: string): Promise<string> {
  const req = http.createHttp()
  try {
    const res = await req.request(url, {
      method: http.RequestMethod.GET,
      connectTimeout: 10000,
      readTimeout: 10000,
    })
    const code = Number(res.responseCode)
    if (code < 200 || code >= 300) {
      throw new Error(`Calibre OPDS 请求失败：HTTP ${code}`)
    }
    return String(res.result)
  } finally {
    req.destroy()
  }
}

function parseOpdsFeed(xml: string, feedUrl: string): OpdsFeed {
  const converter = new convertxml.ConvertXML()
  const parsed = converter.convertToJSObject(xml, {
    trim: true,
    declarationKey: '_declaration',
    instructionKey: '_instruction',
    attributesKey: '_attributes',
    textKey: '_text',
    cdataKey: '_cdata',
    docTypeKey: '_doctype',
    commentKey: '_comment',
    parentKey: '_parent',
    typeKey: '_type',
    nameKey: '_name',
    elementsKey: '_elements',
  }) as XmlNode
  const feed = firstDescendant(parsed, 'feed')
  if (feed === null) throw new Error('响应不是 Atom OPDS feed')

  const publications: CalibreEntry[] = []
  const navigation: NavigationEntry[] = []
  let searchTemplate: string | null = null
  let nextUrl: string | null = null

  directChildren(feed, 'link').forEach((link) => {
    const rel = attr(link, 'rel')
    const href = attr(link, 'href')
    if (href.length === 0) return
    if (tokens(rel).has('search')) searchTemplate = resolveSearchTemplate(href, feedUrl)
    if (tokens(rel).has('next')) nextUrl = resolveHref(href, feedUrl)
  })

  directChildren(feed, 'entry').forEach((entry) => {
    const mapped = parseEntry(entry, feedUrl)
    if (mapped.publication !== null) publications.push(mapped.publication)
    if (mapped.navigation !== null) navigation.push(mapped.navigation)
  })

  return { publications, navigation, searchTemplate, nextUrl }
}

function parseEntry(entry: XmlNode, feedUrl: string): { publication: CalibreEntry | null; navigation: NavigationEntry | null } {
  const title = directText(entry, 'title') || '未命名'
  const atomId = directText(entry, 'id')
  const updated = directText(entry, 'updated') || ''
  const authors = directChildren(entry, 'author')
    .map((author) => directText(author, 'name'))
    .filter((value): value is string => value !== null && value.length > 0)
  const tags = new Set<string>()
  let series = directText(entry, 'series')
  let seriesIndex = numberOrNull(directText(entry, 'series_index'))
  const acquisitions: Acquisition[] = []
  const covers: Array<{ url: string; rank: number }> = []
  let navigationUrl: string | null = null

  directChildren(entry, 'category').forEach((category) => {
    const value = attr(category, 'term') || attr(category, 'label')
    if (value.trim().length > 0) tags.add(value.trim())
  })

  const metadata = extractDisplayMetadata(readEntryContent(entry))
  metadata.tags.forEach((tag) => tags.add(tag))
  if (series === null) series = metadata.series
  if (seriesIndex === null) seriesIndex = metadata.seriesIndex

  directChildren(entry, 'link').forEach((link) => {
    const rel = attr(link, 'rel')
    const type = attr(link, 'type')
    const href = attr(link, 'href')
    if (href.length === 0) return
    const absolute = resolveHref(href, feedUrl)
    if (isAcquisition(rel, type, absolute)) {
      acquisitions.push({ url: absolute, type, formatHint: formatHint(type, absolute) })
    } else if (isCover(rel, type)) {
      covers.push({ url: absolute, rank: coverRank(rel) })
    } else if (isNavigation(rel, type) && navigationUrl === null) {
      navigationUrl = absolute
    }
  })

  const best = selectPreferredAcquisition(acquisitions)
  if (best === null) {
    return {
      publication: null,
      navigation: navigationUrl === null ? null : { title, url: navigationUrl },
    }
  }

  const identity = bookIdentity(best.url)
  const id = Number(identity?.bookId ?? atomId)
  if (!Number.isFinite(id)) return { publication: null, navigation: null }
  const acquisitionByFormat = new Map<string, string>()
  acquisitions.forEach((item) => {
    const format = item.formatHint?.toLowerCase()
    if (format !== undefined && !acquisitionByFormat.has(format)) acquisitionByFormat.set(format, item.url)
  })

  return {
    publication: {
      id,
      title: [title],
      authors: authors.length > 0 ? authors : ['Unknown'],
      formats: distinctFormats(acquisitions),
      tags: Array.from(tags),
      series,
      series_index: seriesIndex,
      libraryId: identity?.libraryId ?? LIBRARY_ID,
      cover: covers.sort((a, b) => a.rank - b.rank)[0]?.url ?? '',
      last_modified: updated,
      acquisitions: acquisitionByFormat,
    },
    navigation: null,
  }
}

function opdsRootUrl(): string {
  const base = normalizeBaseUrl(BASE_URL)
  const root = base.endsWith('/opds') ? base : `${base}/opds`
  return LIBRARY_ID === 'calibre-library' ? root : `${root}?library_id=${encodeURIComponent(LIBRARY_ID)}`
}

function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, '')
}

function expandSearchTemplate(template: string, query: string): string {
  const encoded = encodeURIComponent(query)
  return template.replace('{searchTerms}', encoded).replace('{searchTerms?}', encoded)
}

function resolveSearchTemplate(href: string, feedUrl: string): string {
  return resolveHref(
    href.replace('{searchTerms?}', SEARCH_TERMS_OPTIONAL_SENTINEL).replace('{searchTerms}', SEARCH_TERMS_SENTINEL),
    feedUrl,
  )
    .replace(SEARCH_TERMS_OPTIONAL_SENTINEL, '{searchTerms?}')
    .replace(SEARCH_TERMS_SENTINEL, '{searchTerms}')
}

function resolveHref(href: string, feedUrl: string): string {
  if (href.startsWith('http://') || href.startsWith('https://')) return href
  if (href.startsWith('/')) return `${originOf(feedUrl)}${href}`
  const queryIndex = feedUrl.indexOf('?')
  const withoutQuery = queryIndex >= 0 ? feedUrl.slice(0, queryIndex) : feedUrl
  return `${withoutQuery.slice(0, withoutQuery.lastIndexOf('/') + 1)}${href}`
}

function originOf(url: string): string {
  const match = url.match(/^(https?:\/\/[^/]+)/i)
  return match?.[1] ?? BASE_URL
}

function firstDescendant(node: XmlNode, name: string): XmlNode | null {
  if (localName(node) === name) return node
  for (const child of node._elements ?? []) {
    const found = firstDescendant(child, name)
    if (found !== null) return found
  }
  return null
}

function directChildren(node: XmlNode, name: string): XmlNode[] {
  return (node._elements ?? []).filter((child) => child._type === 'element' && localName(child) === name)
}

function directText(node: XmlNode, name: string): string | null {
  const text = directChildren(node, name)[0]?._elements?.find((child) => child._type === 'text')?._text?.trim()
  return text === undefined || text.length === 0 ? null : text
}

function readEntryContent(entry: XmlNode): string {
  const content = directChildren(entry, 'content')[0]
  if (content === undefined) return ''
  return collectText(content)
}

function collectText(node: XmlNode): string {
  if (node._type === 'text') return node._text ?? ''
  if (localName(node) === 'br') return '\n'
  return (node._elements ?? []).map(collectText).join(localName(node) === 'div' || localName(node) === 'p' ? '\n' : '')
}

function attr(node: XmlNode, key: string): string {
  return node._attributes?.[key] ?? ''
}

function localName(node: XmlNode): string {
  return (node._name ?? '').split(':').pop()?.toLowerCase() ?? ''
}

function tokens(value: string): Set<string> {
  return new Set(value.split(/\s+/).map((token) => token.toLowerCase()).filter((token) => token.length > 0))
}

function isAcquisition(rel: string, type: string, url: string): boolean {
  return rel.toLowerCase().includes('acquisition') || formatHint(type, url) !== null
}

function isCover(rel: string, type: string): boolean {
  const lowerRel = rel.toLowerCase()
  return lowerRel.includes('cover') ||
    lowerRel.includes('image') ||
    lowerRel.includes('thumbnail') ||
    type.toLowerCase().startsWith('image/')
}

function isNavigation(rel: string, type: string): boolean {
  const relTokens = tokens(rel)
  return !rel.toLowerCase().includes('acquisition') &&
    (type.toLowerCase().includes('atom+xml') || rel.length === 0 || relTokens.has('subsection'))
}

function coverRank(rel: string): number {
  const lower = rel.toLowerCase()
  if (lower.includes('thumbnail')) return 2
  if (lower.endsWith('/image') || lower.endsWith('/cover')) return 0
  return 1
}

function formatHint(type: string, url: string): string | null {
  const fromPath = getPathFormat(url)
  if (fromPath !== null) return fromPath
  const mime = type.split(';')[0].trim().toLowerCase()
  if (mime.includes('epub')) return 'epub'
  if (mime.includes('x-mobi8-ebook') || mime.includes('azw3')) return 'azw3'
  if (mime.includes('mobipocket')) return 'mobi'
  if (mime === 'application/pdf' || mime.endsWith('/pdf')) return 'pdf'
  if (mime === 'text/plain') return 'txt'
  if (mime === 'text/markdown' || mime === 'text/x-markdown') return 'md'
  if (mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx'
  if (mime.includes('comicbook+zip') || mime.includes('cbz')) return 'cbz'
  const ext = pathOf(url).split('.').pop()?.toLowerCase()
  return ext !== undefined && knownFormat(ext) ? ext : null
}

function getPathFormat(url: string): string | null {
  const segments = pathOf(url).split('/').filter((segment) => segment.length > 0)
  const lowered = segments.map((segment) => segment.toLowerCase())
  const getIndex = lowered.lastIndexOf('get')
  const candidate = getIndex >= 0 ? lowered[getIndex + 1] : undefined
  return candidate !== undefined && knownFormat(candidate) ? candidate : null
}

function pathOf(url: string): string {
  const withoutQuery = url.split('?')[0].split('#')[0]
  const match = withoutQuery.match(/^https?:\/\/[^/]+(\/.*)?$/i)
  return match?.[1] ?? withoutQuery
}

function knownFormat(format: string): boolean {
  return ['epub', 'azw3', 'mobi', 'pdf', 'txt', 'md', 'docx', 'cbz'].includes(format)
}

function selectPreferredAcquisition(candidates: Acquisition[]): Acquisition | null {
  return [...candidates].sort((a, b) => acquisitionRank(a) - acquisitionRank(b))[0] ?? null
}

function acquisitionRank(candidate: Acquisition): number {
  const hint = candidate.formatHint?.toLowerCase() ?? ''
  const type = candidate.type.toLowerCase()
  if (type.includes('epub') || hint === 'epub') return 0
  if (type.includes('pdf') || hint === 'pdf') return 1
  if (hint === 'txt') return 2
  if (hint === 'md') return 3
  if (knownFormat(hint)) return 10
  if (type.includes('octet-stream')) return 100
  return 101
}

function distinctFormats(acquisitions: Acquisition[]): string[] {
  return Array.from(new Set(
    [...acquisitions]
      .sort((a, b) => acquisitionRank(a) - acquisitionRank(b))
      .map((item) => item.formatHint?.toLowerCase())
      .filter((format): format is string => format !== undefined && format.length > 0),
  ))
}

function bookIdentity(downloadUrl: string): { bookId: string; libraryId: string | null } | null {
  const segments = pathOf(downloadUrl).split('/').filter((segment) => segment.length > 0)
  const lowered = segments.map((segment) => segment.toLowerCase())
  const getIndex = lowered.lastIndexOf('get')
  if (getIndex < 0 || getIndex + 2 >= segments.length) return null
  const bookId = segments[getIndex + 2]
  if (!/^\d+$/.test(bookId)) return null
  return {
    bookId,
    libraryId: segments[getIndex + 3] ?? null,
  }
}

function isCalibreTitleNavigation(url: string): boolean {
  const raw = pathOf(url).split('/').pop() ?? ''
  if (raw.length % 2 !== 0 || !/^[0-9a-f]+$/i.test(raw)) return false
  const parts = raw.match(/.{1,2}/g) ?? []
  return parts.map((hex) => String.fromCharCode(Number.parseInt(hex, 16))).join('').toLowerCase() === 'otitle'
}

function extractDisplayMetadata(content: string): { tags: string[]; series: string | null; seriesIndex: number | null } {
  const lines = content.split(/\r?\n/).map((line) => line.trim()).filter((line) => line.length > 0)
  const tagsLine = lines.find((line) => /^(tags?|标签|標籤)\s*[:：]/i.test(line))
  const seriesLine = lines.find((line) => /^(series|丛书|叢書|系列)\s*[:：]/i.test(line))
  const tags = tagsLine === undefined
    ? []
    : tagsLine.replace(/^(tags?|标签|標籤)\s*[:：]\s*/i, '').split(/[,，;；]/).map((tag) => tag.trim()).filter((tag) => tag.length > 0)
  const rawSeries = seriesLine?.replace(/^(series|丛书|叢書|系列)\s*[:：]\s*/i, '').trim()
  const index = rawSeries?.match(/\[([0-9.]+)]\s*$/)?.[1]
  return {
    tags,
    series: rawSeries?.replace(/\s*\[[0-9.]+]\s*$/, '').trim() || null,
    seriesIndex: numberOrNull(index),
  }
}

function numberOrNull(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value.length === 0) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function entryMatchesQuery(entry: BookMeta, query: string): boolean {
  const needle = query.toLowerCase()
  return entry.title.some((value) => value.toLowerCase().includes(needle)) ||
    entry.authors.some((value) => value.toLowerCase().includes(needle)) ||
    entry.tags.some((value) => value.toLowerCase().includes(needle)) ||
    (entry.series !== null && entry.series.toLowerCase().includes(needle))
}
