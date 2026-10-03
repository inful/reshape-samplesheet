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
    path "${samplesheet.name.replaceFirst(~/\\.[^.]+\\$/, '')}.nfcore.csv"  , emit: csv
    path "versions.yml"                                                       , emit: versions

    script:
    def opts_json = groovy.json.JsonOutput.toJson(opts ?: [:])
    def samplesheet_str = samplesheet.toRealPath()
    def fastq_dir_str  = fastq_dir.toRealPath()
    def output_dir_str = output_dir.toRealPath()
    def basename       = samplesheet.name.replaceFirst(~/\.[^.]+$/, '')
    """
    # Bring SamplesheetReshape + helpers into the task workdir.
    mkdir -p lib
    find ${projectDir}/lib -maxdepth 1 -name '*.groovy' -exec cp -t lib {} +
    cat > reshape.groovy << 'GROOVY'
    SamplesheetReshape.writeReshaped(
        new File(args[0]),
        new File(args[1]),
        new File(args[2]),
        new groovy.json.JsonSlurper().parseText(args[3])
    )
    println 'samplesheet-reshape: OK'
    GROOVY
    groovy -cp lib reshape.groovy \\
        '${output_dir_str}' '${samplesheet_str}' '${fastq_dir_str}' '${opts_json}'

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    """
    touch ${basename}.nfcore.csv
    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """
}