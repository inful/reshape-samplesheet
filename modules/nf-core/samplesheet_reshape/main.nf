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
    # Actionable failure if lib/ did not travel with this component.
    # projectDir is the *including* pipeline's root, so this fires when
    # someone copied only modules/ across. Note that including the
    # sub-workflow without lib/ fails earlier, at Nextflow compile time,
    # with "`SamplesheetReshape` is not defined".
    if [ ! -f lib/SamplesheetReshape.groovy ]; then
        echo "ERROR: no Groovy library at ${projectDir}/lib/. Copy the whole" >&2
        echo "       repo (lib/ + modules/ + subworkflows/), not just modules." >&2
        exit 1
    fi

    # Resolve the REAL output directory.
    #
    # Nextflow stages `output_dir` as a symlink pointing at the path the
    # caller asked for. Three cases:
    #   1. output_dir is that symlink — create and write to its TARGET,
    #      not the symlink. When the target does not exist yet the
    #      symlink is broken, so writing through it lands in the
    #      workdir instead: the directory the caller chose stayed empty
    #      and the CSV was stranded in work/<hash>/.
    #   2. output_dir is a regular file or any other non-dir — clear it
    #      so mkdir can create a real directory.
    #   3. output_dir is already a directory — leave its contents
    #      alone. Anything the user has put there (logs, reports, a
    #      different CSV from a parallel run) stays untouched.
    #
    # A publishDir used to cover case 1, but it published to
    # params.outdir — a global of the *including* pipeline. In a
    # consumer pipeline that is the consumer's own results directory,
    # not the directory they passed to this sub-workflow: the CSV went
    # to their results dir and the directory they asked for stayed
    # empty. Writing to the staged path's target is correct for the CLI
    # and for a consumer.
    #
    # The `rm -f` in case 2 is intentional but not `rm -rf`: a
    # destructive wipe of the whole output_dir would surprise users
    # who have other files alongside the CSV.
    if [ -L ${out_dir_q} ]; then
        out_dir_target=\$(readlink ${out_dir_q})
        mkdir -p "\$out_dir_target"
    elif [ ! -d ${out_dir_q} ]; then
        rm -f ${out_dir_q}
        mkdir -p ${out_dir_q}
        out_dir_target=${out_dir_q}
    else
        out_dir_target=${out_dir_q}
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
        //
        // Each copy goes to a .tmp name and is then renamed into
        // place. Writing straight to the destination truncates it
        // first, so a job killed mid-write (scheduler timeout, full
        // disk, node reboot) left a partial CSV where a good one used
        // to be. rename() within one directory is atomic, so the
        // destination is either the old file or the new one.
        def place = { File dest ->
            // absoluteFile.parentFile, not parentFile: the workdir copy
            // is a bare basename so parentFile is null, and
            // new File(null, String) is an ambiguous overload.
            def tmp = new File(dest.absoluteFile.parentFile, dest.name + '.tmp')
            tmp.bytes = written.bytes
            if (!tmp.renameTo(dest)) {
                // Cross-device or otherwise unrenameable: fall back to
                // a plain copy rather than losing the result.
                dest.bytes = written.bytes
                tmp.delete()
            }
        }
        place(new File(written.name))
        place(new File(outDir, written.name))
    } finally {
        stageDir.deleteDir()
    }
    GROOVY
    # Quoted: the output directory may contain spaces.
    groovy -cp lib reshape.groovy "\${out_dir_target}" ${samplesheet_q} ${fastq_dir_q} ${opts_json_q}

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
    //
    // Writes a real header + one row, not an empty file. The
    // sub-workflow parses this CSV to build its `samples` emit, so a
    // zero-byte stub silently produced zero records and the stub test
    // could not tell a working emit from a broken one.
    """
    cat > ${samplesheet.name.replaceFirst(~/\.[^.]+$/, '')}.nfcore.csv << 'END_CSV'
    sample,fastq_1,fastq_2
    stub_sample,stub_R1.fastq.gz,stub_R2.fastq.gz
    END_CSV
    cat << END_VERSIONS > versions.yml
    "${task.process}":
        samplesheet-reshape: ${workflow.manifest.version}
    END_VERSIONS
    """
}