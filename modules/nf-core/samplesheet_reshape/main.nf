process SAMPLESHEET_RESHAPE {

    tag { "${samplesheet}" }
    label 'process_single'

    // Conda + container for portable execution; falls back to host Groovy.
    conda { params.enable_conda ? "groovy=4.0.21" : null }
    container { params.enable_docker ? "groovy:4.0-jdk21" : null }

    input:
    path samplesheet
    path fastq_dir
    // `output_dir` is a String rather than a path so it isn't staged into
    // the task workdir. The script creates the directory itself, which is
    // more reliable than relying on Nextflow's path staging (which can be
    // a read-only bind-mount in some container setups).
    val output_dir
    val opts

    output:
    path "${samplesheet.name.replaceFirst(~/\.[^.]+$/, '')}.nfcore.csv"  , emit: csv
    path "versions.yml"                                                  , emit: versions

    script:
    // Stringify the opts Map so the shell + Groovy subprocess can parse
    // it cleanly. Empty / null collapses to {}.
    def opts_json = groovy.json.JsonOutput.toJson(opts ?: [:])
    // ${samplesheet}, ${fastq_dir} in the script: block resolve to the
    // staged paths inside the task workdir, which is what we want —
    // never use .toRealPath() here, that bypasses Nextflow's staging.
    """
    # Bring SamplesheetReshape + helpers into the task workdir.
    mkdir -p lib
    find ${projectDir}/lib -maxdepth 1 -name '*.groovy' -exec cp -t lib {} +

    # Make sure the output directory exists before we try to write
    # into it. Using mkdir -p (idempotent) is more reliable than
    # relying on writeReshaped()'s mkdirs() call.
    mkdir -p '${output_dir}'

    # Write the Groovy entry point to a file and run it.
    cat > reshape.groovy << 'GROOVY'
    SamplesheetReshape.writeReshaped(
        new File('${output_dir}'),
        new File('${samplesheet}'),
        new File('${fastq_dir}'),
        new groovy.json.JsonSlurper().parseText('${opts_json}')
    )
    GROOVY
    groovy -cp lib reshape.groovy

    # Move the CSV to a stable name in the workdir so Nextflow can
    # capture it (the output pattern is the basename only, in the workdir).
    BASENAME=\$(echo '${samplesheet}' | sed 's/\\.[^.]*\$//')
    mv '${output_dir}/\${BASENAME}.nfcore.csv' '\${BASENAME}.nfcore.csv'

    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """

    stub:
    """
    BASENAME=\$(echo '${samplesheet}' | sed 's/\\.[^.]*\$//')
    touch \${BASENAME}.nfcore.csv
    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """
}