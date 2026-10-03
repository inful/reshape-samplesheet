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
    def samplesheet_str = samplesheet.toRealPath()
    def fastq_dir_str  = fastq_dir.toRealPath()
    """
    # Bring SamplesheetReshape + helpers into the task workdir.
    # projectDir is the directory of the workflow that includes us
    # (this module's parent pipeline). When run from this repo root
    # that's the lib/ alongside subworkflows/ and modules/.
    mkdir -p lib
    find ${projectDir}/lib -maxdepth 1 -name '*.groovy' -exec cp -t lib {} +
    cat > validate.groovy << 'GROOVY'
    SamplesheetReshape.validate(
        new File(args[0]),
        new File(args[1]),
        new groovy.json.JsonSlurper().parseText(args[2])
    )
    println 'samplesheet-validate: OK'
    GROOVY
    groovy -cp lib validate.groovy \\
        '${samplesheet_str}' '${fastq_dir_str}' '${opts_json}'

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-validate: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    """
    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-validate: ${workflow.manifest.version}
    END_VERSIONS
    """
}