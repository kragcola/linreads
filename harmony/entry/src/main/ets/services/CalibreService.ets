import http from '@ohos.net.http'

export interface BookMeta {
  id: number
  title: string[]
  authors: string[]
  formats: string[]
  tags: string[]
  series: string | null
}

interface SearchResult {
  total_num: number
  book_ids: number[]
}

// Configured via Settings; defaults to local dev
let BASE_URL = 'http://192.168.1.1:8080'
const LIBRARY_ID = 'calibre-library'

export function setCalibreBaseUrl(url: string) {
  BASE_URL = url
}

async function get<T>(path: string): Promise<T> {
  const req = http.createHttp()
  const res = await req.request(`${BASE_URL}${path}`, { method: http.RequestMethod.GET })
  req.destroy()
  return JSON.parse(res.result as string) as T
}

export const CalibreService = {
  async search(query = '', num = 100): Promise<BookMeta[]> {
    const result = await get<SearchResult>(`/ajax/search?query=${encodeURIComponent(query)}&num=${num}`)
    if (!result.book_ids?.length) return []
    const ids = result.book_ids.slice(0, num).join(',')
    const metas = await get<Record<string, BookMeta>>(`/ajax/books?ids=${ids}`)
    return Object.values(metas)
  },

  downloadUrl(id: number, format: string): string {
    return `${BASE_URL}/get/${format}/${id}/${LIBRARY_ID}`
  },
}
