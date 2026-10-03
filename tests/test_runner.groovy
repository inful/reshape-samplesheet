// Shared test scaffolding for SamplesheetReshape. Loaded by each test file
// via:
//   def ctx = evaluate(new File('tests/test_runner.groovy'))
//   def dataDir = ctx.dataDir
//   def fastqDir = ctx.fastqDir
//   def runner = ctx.runner
//
// `runner.test(name) { ... }` runs a test, and `runner.summary()` is
// called at the end of each file (it returns the exit code).

class TestRunner {
    int passed = 0
    int failed = 0
    List<String> failures = []

    void test(String name, Closure body) {
        try {
            body()
            passed++
            println "  PASS  ${name}"
        } catch (Throwable t) {
            failed++
            failures << "${name}: ${t.message}"
            println "  FAIL  ${name}"
            println "        ${t.message}"
        }
    }

    int summary() {
        println ""
        println "Results: ${passed} passed, ${failed} failed"
        if (failed > 0) {
            failures.each { println "  - ${it}" }
        }
        return failed == 0 ? 0 : 1
    }
}

return [
    dataDir: new File('tests/data'),
    fastqDir: new File('tests/fastqs'),
    runner: new TestRunner()
]
