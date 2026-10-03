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
    // ${samplesheet}, ${fastq_dir}, ${output_dir} in the script: block
    // resolve to the staged paths inside the task workdir, which is what
    // we want — never use .toRealPath() here, that bypasses Nextflow's
    // staging and writes outside the workdir.
    """
    # Bring SamplesheetReshape + helpers into the task workdir.
    # projectDir is the directory of the workflow that includes us
    # (this module's parent pipeline). When run from this repo root
    # that's the lib/ alongside subworkflows/ and modules/.
    mkdir -p lib
    find ${projectDir}/lib -maxdepth 1 -name '*.groovy' -exec cp -t lib {} +
    # Use the staged paths directly. writeReshaped() is happy to take
    # either a relative or absolute path.
    groovy -cp lib -e '''
        SamplesheetReshape.validate(
            new File('${samplesheet}'),
            new File('${fastq_dir}'),
            new groovy.json.JsonSlurper().parseText('${opts_json}')
        )
    '''

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