// Calibre Content Server API contract — shared spec across platforms

export interface SearchResult {
  total_num: number
  book_ids: number[]
}

export interface BookMeta {
  id: number
  title: string[]
  authors: string[]
  formats: string[]        // e.g. ["EPUB", "PDF"]
  tags: string[]
  series: string | null
  series_index: number | null
  cover: string            // URL path: /get/cover/<id>/<library>
  last_modified: string    // ISO 8601
}

export interface CalibreConfig {
  baseUrl: string          // e.g. http://192.168.1.x:8080
  username?: string
  password?: string
  libraryId?: string       // default: 'calibre-library'
}
