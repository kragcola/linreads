import { CalibreService, BookMeta } from '../services/CalibreService'
import { BookList } from '../components/BookList'

@Entry
@Component
struct Index {
  @State books: BookMeta[] = []
  @State loading: boolean = true

  aboutToAppear() {
    CalibreService.search().then(result => {
      this.books = result
      this.loading = false
    }).catch(() => { this.loading = false })
  }

  build() {
    Column() {
      Row() {
        Text('Readflow').fontSize(24).fontWeight(FontWeight.Bold)
      }.padding(16).width('100%')

      if (this.loading) {
        LoadingProgress().width(40).height(40)
      } else {
        BookList({ books: this.books })
      }
    }.width('100%').height('100%').backgroundColor($r('sys.color.background'))
  }
}
