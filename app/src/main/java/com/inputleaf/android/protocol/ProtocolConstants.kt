package com.inputleaf.android.protocol

object ProtocolConstants {
    const val DEFAULT_PORT = 24800
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 6
    const val MAX_MESSAGE_LEN = 4 * 1024 * 1024

    fun negotiateMinor(serverMinor: Int): Int = minOf(serverMinor, PROTOCOL_MINOR)

    // Server→Client tags
    const val TAG_HELLO         = "HELO"
    const val TAG_QUERY_INFO    = "QINF"
    const val TAG_ENTER         = "CINN"
    const val TAG_LEAVE         = "COUT"
    const val TAG_KEEPALIVE     = "CALV"
    const val TAG_RESET_OPTIONS = "CROP"
    const val TAG_SET_OPTIONS   = "DSOP"
    const val TAG_KEY_DOWN      = "DKDN"
    const val TAG_KEY_DOWN_LANG = "DKDL"
    const val TAG_KEY_UP        = "DKUP"
    const val TAG_KEY_REPEAT    = "DKRP"
    const val TAG_MOUSE_MOVE    = "DMMV"
    const val TAG_MOUSE_REL     = "DMRM"
    const val TAG_MOUSE_DOWN    = "DMDN"
    const val TAG_MOUSE_UP      = "DMUP"
    const val TAG_MOUSE_WHEEL   = "DMWM"
    const val TAG_INCOMPATIBLE  = "EICV"
    const val TAG_BUSY          = "EBSY"
    const val TAG_UNKNOWN       = "EUNK"
    const val TAG_BAD           = "EBAD"

    // Both directions
    const val TAG_CLIPBOARD      = "DCLP"
    const val TAG_GRAB_CLIPBOARD = "CCLP"

    /** First minor version that splits DCLP into start/chunk/end messages. */
    const val CLIPBOARD_CHUNKED_MINOR = 6

    // DSOP option ids are four ASCII characters packed big-endian.
    val OPTION_CLIPBOARD_SHARING = optionCode("CLPS")
    /** Maximum clipboard the server accepts, in KB; 0 disables sharing. */
    val OPTION_CLIPBOARD_SIZE = optionCode("CLSZ")

    fun optionCode(code: String): Int {
        require(code.length == 4) { "Option codes are four characters" }
        return code.fold(0) { acc, c -> (acc shl 8) or (c.code and 0xFF) }
    }

    // Client→Server write-only tags (never used in parser when-branches)
    const val TAG_HELLO_BACK    = "HELO" // same wire tag as TAG_HELLO — write-only
    const val TAG_DATA_INFO     = "DINF"
    const val TAG_INFO_ACK      = "CIAK"
}
