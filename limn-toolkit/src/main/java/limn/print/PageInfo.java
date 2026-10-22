package limn.print;

import java.util.Objects;

/**
 * Which page a header or a footer is drawn for: its number, how many pages the document has, and
 * the paper. Known only once the document has been cut into pages, which is why a header and a
 * footer are functions of it ({@link Document#header}, {@link Document#footer}).
 *
 * @param number the page's number, from 1
 * @param count  how many pages the document has
 * @param paper  the paper the document is laid out on
 */
public record PageInfo(int number, int count, Paper paper) {

    public PageInfo {
        Objects.requireNonNull(paper, "paper");
        if (number < 1 || count < number) {
            throw new IllegalArgumentException("page " + number + " of " + count);
        }
    }

    /** @return whether this is the first page */
    public boolean isFirst() {
        return number == 1;
    }

    /** @return whether this is the last page */
    public boolean isLast() {
        return number == count;
    }
}
