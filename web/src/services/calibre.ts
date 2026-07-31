import axios from 'axios'

const BASE = (import.meta.env.VITE_CALIBRE_URL ? import.meta.env.VITE_CALIBRE_URL : '/calibre').replace(/\/$/, '')
const DEFAULT_LIBRARY_ID = 'calibre-library'
const MAX_NAVIGATION_DEPTH = 4
const MAX_FEEDS_PER_SESSION = 256

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

type FeedState = {
  entries: CalibreEntry[]
  nextUrl: string | null
  serverSearch: boolean
  visitedUrls: Set<string>
}

type CalibreEntry = BookMeta & {
  acquisitions: Map<string, string>
}

type OpdsFeed = {
  publications: CalibreEntry[]
  navigation: { title: string | null; url: string }[]
  searchTemplate: string | null
  nextUrl: string | null
}

type Acquisition = {
  url: string
  type: string
  formatHint: string | null
}

const cachedById = new Map<number, CalibreEntry>()
const searchStates = new Map<string, FeedState>()
let rootFeedPromise: Promise<OpdsFeed> | null = null
let browseStatePromise: Promise<FeedState> | null = null

export const calibre = {
  async search(query = '', num = 100): Promise<BookMeta[]> {
    const state = await stateFor(query.trim())
    const requested = Math.max(1, num)
    const matches: CalibreEntry[] = []
    let scanIndex = 0

    while (matches.length < requested) {
      while (scanIndex >= state.entries.length && state.nextUrl) {
        await appendNextPage(state)
      }
      if (scanIndex >= state.entries.length) break
      const entry = state.entries[scanIndex]
      scanIndex += 1
      if (state.serverSearch || !query || entryMatchesQuery(entry, query)) {
        matches.push(entry)
        cachedById.set(entry.id, entry)
      }
    }

    return matches.map(stripRuntimeFields)
  },

  async bookMeta(id: number, libraryId = DEFAULT_LIBRARY_ID): Promise<BookMeta> {
    const cached = cachedById.get(id)
    if (cached && (!libraryId || cached.libraryId === libraryId || libraryId === DEFAULT_LIBRARY_ID)) {
      return stripRuntimeFields(cached)
    }
    const found = await findById(id)
    if (found) return stripRuntimeFields(found)
    throw new Error(`Calibre OPDS 中未找到书籍 ${id}`)
  },

  downloadUrl: (id: number, fmt: string, libraryId = DEFAULT_LIBRARY_ID) => {
    const format = fmt.toLowerCase()
    const cached = cachedById.get(id)
    const fromFeed = cached?.acquisitions.get(format)
    if (fromFeed) return fromFeed
    return resolveAgainstBase(`/get/${encodeURIComponent(format)}/${id}/${encodeURIComponent(libraryId)}`, BASE)
  },
}

function stripRuntimeFields(entry: CalibreEntry): BookMeta {
  const { acquisitions: _acquisitions, ...meta } = entry
  return meta
}

async function stateFor(query: string): Promise<FeedState> {
  if (!query) return initialBrowseState()
  const existing = searchStates.get(query)
  if (existing) return existing

  const root = await rootFeed()
  const template = root.searchTemplate
  const state = template
    ? feedState(await fetchFeed(expandSearchTemplate(template, query)), true, expandSearchTemplate(template, query))
    : copyForLocalSearch(await initialBrowseState())
  searchStates.set(query, state)
  return state
}

async function initialBrowseState(): Promise<FeedState> {
  if (browseStatePromise) return browseStatePromise
  browseStatePromise = (async () => {
    const root = await rootFeed()
    if (root.publications.length > 0 || root.navigation.length === 0) {
      return feedState(root, false, opdsRootUrl())
    }

    let navigation = root.navigation
    const visited = new Set<string>([opdsRootUrl()])
    for (let depth = 0; depth < MAX_NAVIGATION_DEPTH; depth += 1) {
      const target = navigation.find((item) => isCalibreTitleNavigation(item.url)) ?? navigation[0]
      if (!target || visited.has(target.url)) throw new Error('OPDS 导航形成循环')
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
  rootFeedPromise ??= fetchFeed(opdsRootUrl())
  return rootFeedPromise
}

function feedState(feed: OpdsFeed, serverSearch: boolean, initialUrl: string, visitedUrls = new Set<string>([initialUrl])): FeedState {
  for (const entry of feed.publications) cachedById.set(entry.id, entry)
  return {
    entries: [...feed.publications],
    nextUrl: feed.nextUrl,
    serverSearch,
    visitedUrls,
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
  if (!next) return
  if (state.visitedUrls.has(next)) throw new Error('OPDS 分页形成循环')
  if (state.visitedUrls.size >= MAX_FEEDS_PER_SESSION) throw new Error('OPDS 分页数量超过安全上限')
  const feed = await fetchFeed(next)
  state.visitedUrls.add(next)
  for (const entry of feed.publications) cachedById.set(entry.id, entry)
  state.entries.push(...feed.publications)
  state.nextUrl = feed.nextUrl
}

async function findById(id: number): Promise<CalibreEntry | null> {
  const state = await initialBrowseState()
  let scanIndex = 0
  while (scanIndex < state.entries.length || state.nextUrl) {
    while (scanIndex >= state.entries.length && state.nextUrl) {
      await appendNextPage(state)
    }
    const entry = state.entries[scanIndex]
    scanIndex += 1
    if (entry?.id === id) {
      cachedById.set(entry.id, entry)
      return entry
    }
  }
  return null
}

async function fetchFeed(url: string): Promise<OpdsFeed> {
  const { data } = await axios.get<string>(url, {
    responseType: 'text',
    timeout: 10_000,
  })
  return parseOpdsFeed(data, url)
}

function parseOpdsFeed(xml: string, feedUrl: string): OpdsFeed {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  const parseError = firstDescendant(doc, 'parsererror')
  if (parseError) throw new Error(parseError.textContent || 'Calibre OPDS XML 解析失败')
  const feed = firstDescendant(doc, 'feed')
  if (!feed) throw new Error('响应不是 Atom OPDS feed')

  const publications: CalibreEntry[] = []
  const navigation: { title: string | null; url: string }[] = []
  let searchTemplate: string | null = null
  let nextUrl: string | null = null

  for (const link of directChildren(feed, 'link')) {
    const rel = link.getAttribute('rel') ?? ''
    const href = link.getAttribute('href')
    if (!href) continue
    if (tokens(rel).has('search')) searchTemplate = resolveSearchTemplate(href, feedUrl)
    if (tokens(rel).has('next')) nextUrl = resolveAgainstBase(href, feedUrl)
  }

  for (const entry of directChildren(feed, 'entry')) {
    const mapped = parseEntry(entry, feedUrl)
    if (mapped.kind === 'publication') publications.push(mapped.entry)
    if (mapped.kind === 'navigation') navigation.push(mapped.navigation)
  }

  return { publications, navigation, searchTemplate, nextUrl }
}

function parseEntry(entry: Element, feedUrl: string):
  | { kind: 'publication'; entry: CalibreEntry }
  | { kind: 'navigation'; navigation: { title: string | null; url: string } }
  | { kind: 'skip' } {
  const title = directText(entry, 'title') || '未命名'
  const atomId = directText(entry, 'id')
  const updated = directText(entry, 'updated')
  const authors = directChildren(entry, 'author')
    .map((author) => directText(author, 'name'))
    .filter((value): value is string => Boolean(value))
  const tags = new Set<string>()
  let series: string | null = null
  let seriesIndex: number | null = null
  const acquisitions: Acquisition[] = []
  const covers: { url: string; rank: number }[] = []
  let navigationUrl: string | null = null

  for (const category of directChildren(entry, 'category')) {
    const value = category.getAttribute('term') ?? category.getAttribute('label')
    if (value?.trim()) tags.add(value.trim())
  }

  for (const child of Array.from(entry.children)) {
    const local = child.localName.toLowerCase()
    if (local === 'series') series = child.textContent?.trim() || null
    if (local === 'series_index') seriesIndex = numberOrNull(child.textContent)
  }

  const metadata = extractDisplayMetadata(readEntryContent(entry))
  metadata.tags.forEach((tag) => tags.add(tag))
  if (!series) series = metadata.series
  if (seriesIndex == null) seriesIndex = metadata.seriesIndex

  for (const link of directChildren(entry, 'link')) {
    const rel = link.getAttribute('rel') ?? ''
    const type = link.getAttribute('type') ?? ''
    const href = link.getAttribute('href')
    if (!href) continue
    const absolute = resolveAgainstBase(href, feedUrl)
    if (isAcquisition(rel, type, absolute)) {
      acquisitions.push({ url: absolute, type, formatHint: formatHint(type, absolute) })
    } else if (isCover(rel, type)) {
      covers.push({ url: absolute, rank: coverRank(rel) })
    } else if (isNavigation(rel, type) && !navigationUrl) {
      navigationUrl = absolute
    }
  }

  const best = selectPreferredAcquisition(acquisitions)
  if (!best) {
    return navigationUrl
      ? { kind: 'navigation', navigation: { title, url: navigationUrl } }
      : { kind: 'skip' }
  }

  const identity = bookIdentity(best.url)
  const id = Number(identity?.bookId ?? atomId)
  if (!Number.isFinite(id)) return { kind: 'skip' }
  const formats = distinctFormats(acquisitions)
  const acquisitionByFormat = new Map<string, string>()
  for (const acquisition of acquisitions) {
    const format = acquisition.formatHint?.toLowerCase()
    if (format && !acquisitionByFormat.has(format)) acquisitionByFormat.set(format, acquisition.url)
  }

  return {
    kind: 'publication',
    entry: {
      id,
      title: [title],
      authors: authors.length ? authors : ['Unknown'],
      formats,
      tags: [...tags],
      series,
      series_index: seriesIndex,
      libraryId: identity?.libraryId ?? DEFAULT_LIBRARY_ID,
      cover: covers.sort((a, b) => a.rank - b.rank)[0]?.url ?? '',
      last_modified: updated ?? '',
      acquisitions: acquisitionByFormat,
    },
  }
}

function opdsRootUrl(): string {
  return BASE.endsWith('/opds') ? BASE : `${BASE}/opds`
}

function expandSearchTemplate(template: string, query: string): string {
  const encoded = encodeURIComponent(query)
  return template.replace('{searchTerms}', encoded).replace('{searchTerms?}', encoded)
}

function resolveSearchTemplate(href: string, baseUrl: string): string {
  return resolveAgainstBase(
    href.replace('{searchTerms?}', 'READFLOW_SEARCH_TERMS_OPTIONAL').replace('{searchTerms}', 'READFLOW_SEARCH_TERMS'),
    baseUrl,
  )
    .replace('READFLOW_SEARCH_TERMS_OPTIONAL', '{searchTerms?}')
    .replace('READFLOW_SEARCH_TERMS', '{searchTerms}')
}

function resolveAgainstBase(href: string, baseUrl: string): string {
  if (/^https?:\/\//i.test(href)) return href
  const absoluteBase = /^https?:\/\//i.test(baseUrl) ? baseUrl : new URL(baseUrl, window.location.origin).toString()
  const resolved = new URL(href, absoluteBase)
  return resolved.origin === window.location.origin ? `${resolved.pathname}${resolved.search}${resolved.hash}` : resolved.toString()
}

function directChildren(element: ParentNode, name: string): Element[] {
  return Array.from(element.children).filter((child) => child.localName.toLowerCase() === name)
}

function firstDescendant(root: ParentNode, name: string): Element | null {
  const all = root instanceof Document ? root.getElementsByTagName('*') : (root as Element).getElementsByTagName('*')
  return Array.from(all).find((child) => child.localName.toLowerCase() === name) ?? null
}

function directText(element: ParentNode, name: string): string | null {
  const text = directChildren(element, name)[0]?.textContent?.trim()
  return text || null
}

function tokens(value: string): Set<string> {
  return new Set(value.split(/\s+/).map((token) => token.toLowerCase()).filter(Boolean))
}

function isAcquisition(rel: string, type: string, url: string): boolean {
  return rel.toLowerCase().includes('acquisition') || Boolean(formatHint(type, url))
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
    (type.toLowerCase().includes('atom+xml') || !rel || relTokens.has('subsection'))
}

function coverRank(rel: string): number {
  const lower = rel.toLowerCase()
  if (lower.includes('thumbnail')) return 2
  if (lower.endsWith('/image') || lower.endsWith('/cover')) return 0
  return 1
}

function formatHint(type: string, url: string): string | null {
  const fromPath = getPathFormat(url)
  if (fromPath) return fromPath
  const mime = type.split(';')[0].trim().toLowerCase()
  if (mime.includes('epub')) return 'epub'
  if (mime.includes('x-mobi8-ebook') || mime.includes('azw3')) return 'azw3'
  if (mime.includes('mobipocket')) return 'mobi'
  if (mime === 'application/pdf' || mime.endsWith('/pdf')) return 'pdf'
  if (mime === 'text/plain') return 'txt'
  if (mime === 'text/markdown' || mime === 'text/x-markdown') return 'md'
  if (mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx'
  if (mime.includes('comicbook+zip') || mime.includes('cbz')) return 'cbz'
  const ext = new URL(url, window.location.origin).pathname.split('.').pop()?.toLowerCase()
  return ext && knownFormat(ext) ? ext : null
}

function getPathFormat(url: string): string | null {
  const segments = new URL(url, window.location.origin).pathname.split('/').filter(Boolean)
  const getIndex = segments.map((segment) => segment.toLowerCase()).lastIndexOf('get')
  const candidate = getIndex >= 0 ? segments[getIndex + 1]?.toLowerCase() : null
  return candidate && knownFormat(candidate) ? candidate : null
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
  const sorted = [...acquisitions].sort((a, b) => acquisitionRank(a) - acquisitionRank(b))
  return [...new Set(sorted.map((item) => item.formatHint?.toLowerCase()).filter((format): format is string => Boolean(format)))]
}

function bookIdentity(downloadUrl: string): { bookId: string; libraryId: string | null } | null {
  const segments = new URL(downloadUrl, window.location.origin).pathname.split('/').filter(Boolean)
  const getIndex = segments.map((segment) => segment.toLowerCase()).lastIndexOf('get')
  if (getIndex < 0 || getIndex + 2 >= segments.length) return null
  const bookId = segments[getIndex + 2]
  if (!/^\d+$/.test(bookId)) return null
  return {
    bookId,
    libraryId: segments[getIndex + 3] || null,
  }
}

function isCalibreTitleNavigation(url: string): boolean {
  const raw = new URL(url, window.location.origin).pathname.split('/').pop() ?? ''
  if (raw.length % 2 !== 0 || !/^[0-9a-f]+$/i.test(raw)) return false
  const decoded = raw.match(/.{1,2}/g)?.map((hex) => String.fromCharCode(Number.parseInt(hex, 16))).join('')
  return decoded?.toLowerCase() === 'otitle'
}

function readEntryContent(entry: Element): string {
  const content = directChildren(entry, 'content')[0]
  return content?.textContent ?? ''
}

function extractDisplayMetadata(content: string): { tags: string[]; series: string | null; seriesIndex: number | null } {
  const lines = content.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  const tagsLine = lines.find((line) => /^(tags?|标签|標籤)\s*[:：]/i.test(line))
  const seriesLine = lines.find((line) => /^(series|丛书|叢書|系列)\s*[:：]/i.test(line))
  const tags = tagsLine
    ? tagsLine.replace(/^(tags?|标签|標籤)\s*[:：]\s*/i, '').split(/[,，;；]/).map((tag) => tag.trim()).filter(Boolean)
    : []
  const rawSeries = seriesLine?.replace(/^(series|丛书|叢書|系列)\s*[:：]\s*/i, '').trim()
  const index = rawSeries?.match(/\[([0-9.]+)]\s*$/)?.[1]
  return {
    tags,
    series: rawSeries?.replace(/\s*\[[0-9.]+]\s*$/, '').trim() || null,
    seriesIndex: numberOrNull(index),
  }
}

function numberOrNull(value: string | null | undefined): number | null {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function entryMatchesQuery(entry: BookMeta, query: string): boolean {
  const needle = query.toLowerCase()
  return entry.title.some((value) => value.toLowerCase().includes(needle)) ||
    entry.authors.some((value) => value.toLowerCase().includes(needle)) ||
    entry.tags.some((value) => value.toLowerCase().includes(needle)) ||
    Boolean(entry.series?.toLowerCase().includes(needle))
}
