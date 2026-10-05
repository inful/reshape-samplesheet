/**
 * One logical line of a CSV-shaped text, paired with its 1-based
 * source line number. The line number refers to the first
 * physical line in the file that contributed to the content —
 * for multi-line quoted cells this is the line where the cell
 * was opened.
 *
 * Defined as its own file (rather than nested inside
 * {@link CsvLines}) so Groovy's classpath auto-loader can resolve
 * it from {@code lib/CsvLine.groovy} without depending on
 * {@code CsvLines} being loaded first.
 */
class CsvLine {
    final String content
    final int lineNumber
    CsvLine(String content, int lineNumber) {
        this.content = content
        this.lineNumber = lineNumber
    }
}
