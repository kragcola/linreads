import axios from 'axios'

const BASE = import.meta.env.VITE_CALIBRE_URL ? import.meta.env.VITE_CALIBRE_URL : '/calibre'

export interface BookMeta {
  id: number
  title: string[]
  authors: string[]
  formats: string[]
  tags: string[]
  series: string | null
  series_index: number | null
}

export const calibre = {
  async search(query = '', num = 100): Promise<BookMeta[]> {
    const { data: sr } = await axios.get<{ total_num: number; book_ids: number[] }>(`${BASE}/ajax/search`, {
      params: { query, num },
    })
    if (!sr.book_ids.length) return []
    const { data: metas } = await axios.get<Record<string, BookMeta>>(`${BASE}/ajax/books`, {
      params: { ids: sr.book_ids.slice(0, num).join(',') },
    })
    return Object.values(metas)
  },

  async bookMeta(id: number, libraryId = 'calibre-library'): Promise<BookMeta> {
    return (await axios.get(`${BASE}/ajax/book/${id}/${libraryId}`)).data
  },

  downloadUrl: (id: number, fmt: string, libraryId = 'calibre-library') =>
    `${BASE}/get/${fmt}/${id}/${libraryId}`,
}
