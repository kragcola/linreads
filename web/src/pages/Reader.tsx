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
      const fmt = meta.formats.find((f) => f === 'EPUB') ?? meta.formats.find((f) => f === 'PDF')
      if (!fmt) return
      setFormat(fmt)
      if (fmt === 'EPUB' && ref.current) {
        const book = ePub(calibre.downloadUrl(Number(id), 'EPUB'))
        book.renderTo(ref.current, { width: '100%', height: '100%' })
      } else if (fmt === 'PDF') {
        setPdfUrl(calibre.downloadUrl(Number(id), 'PDF'))
      }
    })
  }, [id])

  if (pdfUrl) return <iframe src={pdfUrl} style={{ width: '100%', height: '100vh', border: 0 }} title="PDF" />

  return <div ref={ref} style={{ width: '100%', height: '100vh' }}>{!format && '加载中…'}</div>
}
