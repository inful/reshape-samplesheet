/**
 * Low-level path utilities shared by the parser, reshaper, and
 * validator. Not part of the public API; called from
 * {@link SamplesheetReshape} and its collaborators.
 */
class CsvSupport {

    /**
     * Convert any path-like value to a {@link File}. Accepts {@code File},
     * any {@link CharSequence} (e.g. {@code String}), and any
     * {@link java.nio.file.Path} (the parent of Nextflow's wrapper
     * path type). Anything else is rejected.
     */
    static File asFile(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("path cannot be null")
        }
        if (o instanceof File) {
            return (File) o
        }
        if (o instanceof CharSequence) {
            return new File(o.toString())
        }
        if (o instanceof java.nio.file.Path) {
            return ((java.nio.file.Path) o).toFile()
        }
        throw new IllegalArgumentException(
            "Cannot interpret ${o.class.name} as a file path: ${o}"
        )
    }
}
