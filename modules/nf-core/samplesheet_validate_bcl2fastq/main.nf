// POSIX-safe shell quoting for a value interpolated into the script:
// block. Defined at script scope rather than inside the script: block
// because Nextflow's DSL parser does not allow calling a closure defined
// there. See the sibling samplesheet_reshape module for the full note.
def shQuote(String s) {
    return "'" + s.replace("'", "'\"'\"'") + "'"
}

process SAMPLESHEET_VALIDATE_BCL2FASTQ {

    tag { "${samplesheet}" }
    label 'process_single'

    // Conda + container for portable execution; falls back to host Groovy.
    conda { params.enable_conda ? "groovy=4.0.21" : null }
    container { params.enable_docker ? "groovy:4.0-jdk21" : null }

    input:
    path samplesheet
    val opts

    output:
    path "versions.yml"  , emit: versions

    script:
    // Stringify the opts Map so the shell + Groovy subprocess can parse
    // it cleanly. Empty / null collapses to {}.
    def opts_json = groovy.json.JsonOutput.toJson(opts ?: [:])
    // ${samplesheet} in the script: block resolves to the staged path
    // inside the task workdir, which is what we want — never use
    // .toRealPath() here, that bypasses Nextflow's staging and writes
    // outside the workdir.
    //
    // Note there is deliberately NO fastq_dir input. This module is
    // the *pre*-bcl2fastq gate: it runs before the sequencer's fastqs
    // exist, so the only thing it can check is the samplesheet itself.
    // See the sibling samplesheet_validate module for the
    // *post*-bcl2fastq shape (samplesheet + fastq_dir).
    def samplesheet_q = shQuote(samplesheet.toString())
    def opts_json_q   = shQuote(opts_json)
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
    # A non-zero exit here fails the task, which fails the workflow.
    # That IS the output: the process either succeeds (samplesheet is
    # demultiplexable) or throws IllegalArgumentException listing every
    # problem at once. Nothing is written on success — there is no
    # artifact to keep.
    cat > validate_bcl2fastq.groovy << 'GROOVY'
    SamplesheetReshape.validateBcl2fastq(
        new File(args[0]),
        new groovy.json.JsonSlurper().parseText(args[1])
    )
    GROOVY
    groovy -cp lib validate_bcl2fastq.groovy ${samplesheet_q} ${opts_json_q}

    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-validate-bcl2fastq: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    """
    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-validate-bcl2fastq: ${workflow.manifest.version}
    END_VERSIONS
    """
}