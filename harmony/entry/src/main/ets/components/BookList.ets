import { BookMeta } from '../services/CalibreService'

@Component
export struct BookList {
  @Prop books: BookMeta[] = []

  build() {
    List({ space: 8 }) {
      ForEach(this.books, (book: BookMeta) => {
        ListItem() {
          Column({ space: 4 }) {
            Text(book.title[0] ?? '未知书名').fontSize(16).fontWeight(FontWeight.Medium).maxLines(2)
            Text(book.authors.join(' / ')).fontSize(13).fontColor($r('sys.color.font_secondary')).maxLines(1)
            Text(book.formats.join(' · ')).fontSize(12).fontColor($r('sys.color.font_tertiary'))
          }
          .alignItems(HorizontalAlign.Start)
          .padding(12)
          .width('100%')
          .backgroundColor($r('sys.color.comp_background_list'))
          .borderRadius(8)
        }
      }, (book: BookMeta) => book.id.toString())
    }
    .padding({ left: 16, right: 16 })
    .width('100%')
    .layoutWeight(1)
  }
}
