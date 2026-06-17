import { useEffect, useState } from 'react'
import { calibre, BookMeta } from '../services/calibre'
import { useNavigate } from 'react-router-dom'

export default function Library() {
  const [books, setBooks] = useState<BookMeta[]>([])
  const [loading, setLoading] = useState(true)
  const nav = useNavigate()

  useEffect(() => {
    calibre.search().then(setBooks).finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="center">加载中…</div>

  return (
    <div className="library">
      <h1>书库</h1>
      <div className="book-grid">
        {books.map((b) => (
          <div key={b.id} className="book-card" onClick={() => nav(`/read/${b.id}`)}>
            <img src={`/calibre/get/cover/${b.id}/calibre-library`} alt="" onError={(e) => (e.currentTarget.style.display = 'none')} />
            <div className="book-title">{b.title[0]}</div>
            <div className="book-author">{b.authors.join(' / ')}</div>
            <div className="book-formats">{b.formats.join(' · ')}</div>
          </div>
        ))}
      </div>
    </div>
  )
}
