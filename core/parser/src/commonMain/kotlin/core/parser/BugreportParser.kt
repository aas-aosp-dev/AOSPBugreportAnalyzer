package core.parser

/** Placeholder for future bugreport parsing pipeline. */
class BugreportParser {
    fun parse(raw: String): ParsedBugreport = ParsedBugreport(summary = "Parser not implemented yet")
}

data class ParsedBugreport(val summary: String)
