// POSIX-safe shell quoting for a value interpolated into the script:
// block. Defined at script scope rather than inside the script: block
// because Nextflow's DSL parser does not allow calling a closure defined
// there. See the sibling samplesheet_reshape module for the full note.
def shQuote(String s) {
    return "'" + s.replace("'", "'\"'\"'") + "'"
}

process SAMPLESHEET_VALIDATE {

    tag { "${samplesheet}" }
    label 'process_single'

    // Conda + container for portable execution; falls back to host Groovy.
    conda { params.enable_conda ? "groovy=4.0.21" : null }
    container { params.enable_docker ? "groovy:4.0-jdk21" : null }

    input:
    path samplesheet
    path fastq_dir
    val opts

    output:
    path "versions.yml"  , emit: versions

    script:
    // Stringify the opts Map so the shell + Groovy subprocess can parse
    // it cleanly. Empty / null collapses to {}.
    def opts_json = groovy.json.JsonOutput.toJson(opts ?: [:])
    def samplesheet_q = shQuote(samplesheet.toString())
    def fastq_dir_q   = shQuote(fastq_dir.toString())
    def opts_json_q   = shQuote(opts_json)
    // ${samplesheet}, ${fastq_dir}, ${output_dir} in the script: block
    // resolve to the staged paths inside the task workdir, which is what
    // we want — never use .toRealPath() here, that bypasses Nextflow's
    // staging and writes outside the workdir.
    """
    # Bring SamplesheetReshape + helpers into the task workdir.
    # projectDir is the directory of the workflow that includes us
    # (this module's parent pipeline). When run from this repo root
    # that's the lib/ alongside subworkflows/ and modules/.
    #
    mkdir -p lib
    for f in ${projectDir}/lib/*.groovy; do
        [ -f "\$f" ] && cp "\$f" lib/
    done

    # Write the Groovy entry point to a file (groovy -e with multi-line
    # scripts parses indentation awkwardly). Then run it.
    #
    # Paths arrive as command-line arguments rather than being pasted
    # into the Groovy source as string literals: a literal has to be
    # escaped for the Groovy parser, an argument does not. This is why
    # an apostrophe in the filename used to produce a broken
    # validate.groovy even though the heredoc itself is safe from bash.
    cat > validate.groovy << 'GROOVY'
    SamplesheetReshape.validate(
        new File(args[0]),
        new File(args[1]),
        new groovy.json.JsonSlurper().parseText(args[2])
    )
    GROOVY
    groovy -cp lib validate.groovy ${samplesheet_q} ${fastq_dir_q} ${opts_json_q}

    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-validate: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    """
    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-validate: ${workflow.manifest.version}
    END_VERSIONS
    """
}