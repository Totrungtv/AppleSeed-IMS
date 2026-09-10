package vn.appleseed.ims

class UserService : AppleSeedImsBridge.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", command)
            )

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }

            process.waitFor()

            buildString {
                append(output)
                if (error.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr] ").append(error)
                }
            }.trim()

        } catch (e: Exception) {
            "Shell error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}