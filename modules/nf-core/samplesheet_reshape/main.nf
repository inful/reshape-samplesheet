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
    path "${output_dir}/${samplesheet.name.replaceFirst(~/\.[^.]+$/, '')}.nfcore.csv"  , emit: csv
    path "versions.yml"                                                                   , emit: versions

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
    mkdir -p lib
    find ${projectDir}/lib -maxdepth 1 -name '*.groovy' -exec cp -t lib {} +

    # Write the Groovy entry point to a file (groovy -e with multi-line
    # scripts parses indentation awkwardly). Then run it.
    cat > reshape.groovy << 'GROOVY'
    SamplesheetReshape.writeReshaped(
        new File('${output_dir}'),
        new File('${samplesheet}'),
        new File('${fastq_dir}'),
        new groovy.json.JsonSlurper().parseText('${opts_json}')
    )
    GROOVY
    groovy -cp lib reshape.groovy

    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    """
    touch ${output_dir}/${samplesheet.name.replaceFirst(~/\.[^.]+$/, '')}.nfcore.csv
    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """
}