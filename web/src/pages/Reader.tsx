import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import ePub from 'epubjs'
import { calibre } from '../services/calibre'

export default function Reader() {
  const { id } = useParams<{ id: string }>()
  const ref = useRef<HTMLDivElement>(null)
  const [format, setFormat] = useState<string | null>(null)
  const [pdfUrl, setPdfUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    calibre.bookMeta(Number(id)).then((meta) => {
      const fmt = meta.formats.find((f) => f.toLowerCase() === 'epub') ?? meta.formats.find((f) => f.toLowerCase() === 'pdf')
      if (!fmt) return
      setFormat(fmt)
      if (fmt.toLowerCase() === 'epub' && ref.current) {
        const book = ePub(calibre.downloadUrl(Number(id), fmt, meta.libraryId))
        book.renderTo(ref.current, { width: '100%', height: '100%' })
      } else if (fmt.toLowerCase() === 'pdf') {
        setPdfUrl(calibre.downloadUrl(Number(id), fmt, meta.libraryId))
      }
    })
  }, [id])

  if (pdfUrl) return <iframe src={pdfUrl} style={{ width: '100%', height: '100vh', border: 0 }} title="PDF" />

  return <div ref={ref} style={{ width: '100%', height: '100vh' }}>{!format && '加载中…'}</div>
}
