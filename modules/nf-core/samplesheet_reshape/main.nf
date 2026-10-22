// POSIX-safe shell quoting for a value interpolated into the script:
// block. Wrapping in single quotes handles spaces, `;`, `&` and `$`; an
// embedded apostrophe is handled by closing the quote, escaping it, and
// reopening ('"'"'). An apostrophe is legal in a filename on macOS,
// Linux and Windows and does occur in real data (e.g. /data/O'Brien_runs/).
// Defined at script scope rather than inside the script: block because
// Nextflow's DSL parser does not allow calling a closure defined there.
//
// Without this, `--outdir "/tmp/O'Brien_out"` or a samplesheet named
// `O'Brien_sheet.csv` produced "syntax error near unexpected token `)'"
// and the job failed.
def shQuote(String s) {
    return "'" + s.replace("'", "'\"'\"'") + "'"
}

process SAMPLESHEET_RESHAPE {

    tag { "${samplesheet}" }
    label 'process_single'

    // Conda + container for portable execution; falls back to host Groovy.
    conda { params.enable_conda ? "groovy=4.0.21" : null }
    container { params.enable_docker ? "groovy:4.0-jdk21" : null }

    // Publish the captured CSV to the directory the user actually asked
    // for. This is what makes a not-yet-existing --outdir work: the
    // `output_dir` input is staged as a *symlink*, which is broken when
    // the target does not exist, so the script's mkdir ends up creating
    // a workdir-local directory and the CSV never reaches the intended
    // path. params.outdir is the un-staged value the caller passed, so
    // publishing to it uses (and creates) the real directory.
    //
    // Disabled when unset, so a consumer including this module without
    // a params.outdir is unaffected; they get the CSV via the `csv` emit.
    publishDir "${params.outdir}", mode: 'copy', overwrite: true, enabled: params.outdir != null

    input:
    path samplesheet
    path fastq_dir
    path output_dir
    val opts

    output:
    // Output glob: a single .nfcore.csv file in the workdir. The
    // Groovy step below writes it there for capture and copies it
    // into output_dir for the user.
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
    def out_dir_q      = shQuote(output_dir_str)
    def basename_q     = shQuote("${basename}.nfcore.csv")
    def samplesheet_q  = shQuote(samplesheet_str)
    def fastq_dir_q    = shQuote(fastq_dir_str)
    def opts_json_q    = shQuote(opts_json)
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
    # The `rm -f` on output_dir is intentional but not `rm -rf`:
    # a destructive wipe of the whole output_dir would surprise users
    # who have other files alongside the CSV. We only clear whatever
    # pre-existing path is in the way of the directory, then leave
    # case 3 (real existing directory) alone.
    if [ ! -d ${out_dir_q} ]; then
        rm -f ${out_dir_q}
        mkdir -p ${out_dir_q}
    fi
    # Do NOT delete the previous CSV here. writeReshaped() throws on a
    # malformed samplesheet, and an earlier version of this script
    # removed the old file first — so a run that failed left the user
    # with no CSV at all, losing the last good result. The Groovy step
    # below stages into a workdir-local directory and only places the
    # file once the reshape has succeeded.

    # Write the Groovy entry point to a file and run it.
    #
    # Paths arrive as command-line arguments rather than being pasted
    # into the Groovy source as string literals: a literal has to be
    # escaped for the Groovy parser, an argument does not. This is why
    # an apostrophe in the filename used to produce a broken
    # reshape.groovy even though the heredoc itself is safe from bash.
    cat > reshape.groovy << 'GROOVY'
    // Stage into a workdir-local directory first, then place the file.
    // writeReshaped throws on a malformed samplesheet; if it wrote
    // straight into output_dir the previous CSV would already be gone
    // by the time it threw, leaving the user with no result at all.
    // Staging means a failed run leaves the last good CSV untouched.
    def outDir    = new File(args[0])
    def stageDir  = new File('stage_reshape')
    stageDir.mkdirs()
    try {
        def written = SamplesheetReshape.writeReshaped(
            stageDir,
            new File(args[1]),
            new File(args[2]),
            new groovy.json.JsonSlurper().parseText(args[3])
        )
        outDir.mkdirs()
        // Two copies: the workdir root copy is what Nextflow's output
        // glob captures, the outDir copy is what the user asked for.
        // Moving instead of copying left --outdir empty with the CSV
        // stranded in work/<hash>/.
        new File(written.name).bytes = written.bytes
        new File(outDir, written.name).bytes = written.bytes
    } finally {
        stageDir.deleteDir()
    }
    GROOVY
    groovy -cp lib reshape.groovy ${out_dir_q} ${samplesheet_q} ${fastq_dir_q} ${opts_json_q}

    # The CSV itself is already in the workdir — the Groovy step wrote it
    # there via stage_reshape and the cleanup above removed only the
    # staging directory. Nothing else to do here; the output globs above
    # pick it up from the workdir.

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