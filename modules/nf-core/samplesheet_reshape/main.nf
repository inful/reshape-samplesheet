process SAMPLESHEET_RESHAPE {

    tag { "${samplesheet}" }
    label 'process_single'

    // Conda + container for portable execution; falls back to host Groovy.
    conda { params.enable_conda ? "groovy=4.0.21" : null }
    container { params.enable_docker ? "groovy:4.0-jdk21" : null }

    input:
    path samplesheet
    path fastq_dir
    path output_dir
    val opts

    output:
    // Output glob: a single .nfcore.csv file in the workdir. The
    // module script writes the file into `output_dir` (the staged
    // directory) and then mv's it into the workdir for capture.
    path "${samplesheet.name.replaceFirst(~/\.[^.]+$/, '')}.nfcore.csv"  , emit: csv
    path "versions.yml"                                                  , emit: versions

    script:
    // Stringify the opts Map so the shell + Groovy subprocess can parse
    // it cleanly. Empty / null collapses to {}.
    def opts_json = groovy.json.JsonOutput.toJson(opts ?: [:])
    // Resolve the staged paths explicitly. .toString() on the path
    // inputs gives the workdir-relative staged path (or absolute, in
    // some Nextflow stage modes), which is what we pass to Groovy.
    def samplesheet_str = samplesheet.toString()
    def fastq_dir_str  = fastq_dir.toString()
    def output_dir_str = output_dir.toString()
    def basename       = samplesheet.name.replaceFirst(~/\.[^.]+$/, '')
    """
    # Bring SamplesheetReshape + helpers into the task workdir.
    #
    mkdir -p lib
    for f in ${projectDir}/lib/*.groovy; do
        [ -f "\$f" ] && cp "\$f" lib/
    done

    # Make sure output_dir is a writable directory. Three cases:
    #   1. output_dir doesn't exist yet — mkdir -p creates it.
    #   2. output_dir is a regular file, a symlink (incl. broken
    #      symlinks to a non-existent target, which is what Nextflow
    #      creates with checkIfExists: false), or any other non-dir
    #      — we need to clear it so mkdir can create a real directory.
    #   3. output_dir is already a directory — leave its contents
    #      alone. Anything the user has put there (logs, reports,
    #      a different CSV from a parallel run) stays untouched.
    #
    # `-d` follows symlinks, so a symlink to a directory is treated
    # as a directory (case 3, correct). A symlink to a non-existent
    # path is treated as not-a-directory (case 2, correct — we'd
    # otherwise fail at mkdir).
    #
    # The `rm -f ${output_dir_str}` is intentional but not `rm -rf`:
    # a destructive wipe of the whole output_dir would surprise users
    # who have other files alongside the CSV. We only clear whatever
    # pre-existing path is in the way of the directory, then leave
    # case 3 (real existing directory) alone.
    if [ ! -d '${output_dir_str}' ]; then
        rm -f '${output_dir_str}'
        mkdir -p '${output_dir_str}'
    fi
    # Remove just the specific CSV we're about to regenerate, not
    # the whole directory — leaves any other files the user has put
    # there alone.
    rm -f '${output_dir_str}/${basename}.nfcore.csv'

    # Write the Groovy entry point to a file and run it.
    cat > reshape.groovy << 'GROOVY'
    SamplesheetReshape.writeReshaped(
        new File('${output_dir_str}'),
        new File('${samplesheet_str}'),
        new File('${fastq_dir_str}'),
        new groovy.json.JsonSlurper().parseText('${opts_json}')
    )
    GROOVY
    groovy -cp lib reshape.groovy

    # Move the CSV into the workdir (the output glob is basename only)
    # so Nextflow can capture it.
    if [ -f '${output_dir_str}/${basename}.nfcore.csv' ]; then
        mv '${output_dir_str}/${basename}.nfcore.csv' '${basename}.nfcore.csv'
    fi

    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    // Stub doesn't have access to variables from the main script: block,
    // so inline the regex + the version label.
    """
    touch ${samplesheet.name.replaceFirst(~/\.[^.]+$/, '')}.nfcore.csv
    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """
}