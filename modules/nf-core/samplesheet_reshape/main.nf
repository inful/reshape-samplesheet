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

    // After the process completes, copy the emitted CSV and versions
    // file into the user's output_dir on the host filesystem. This is
    // what nf-core modules use to surface outputs to the pipeline
    // results directory; without it the files stay inside the workdir
    // and downstream `ch_out.csv.collect()` returns work-dir paths.
    publishDir "${output_dir}", mode: 'copy', overwrite: true

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
    mkdir -p lib
    find ${projectDir}/lib -maxdepth 1 -name '*.groovy' -exec cp -t lib {} +

    # Make sure the output directory exists. When output_dir is
    # declared checkIfExists: false in the nf-test, Nextflow creates
    # it as an EMPTY FILE placeholder (not a directory). Force-create
    # a real directory here so the Groovy file write below can succeed.
    rm -rf '${output_dir_str}'
    mkdir -p '${output_dir_str}'

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