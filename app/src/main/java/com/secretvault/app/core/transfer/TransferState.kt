package com.secretvault.app.core.transfer

/** What a file-share sender or receiver is doing, for the UI. */
sealed interface TransferState {
    data object Idle : TransferState
    /** Receiver is advertising as [name]; sender is connecting or waiting for the receiver to accept. */
    data class Waiting(val name: String) : TransferState
    data class AwaitingAccept(val peerName: String, val count: Int, val bytes: Long) : TransferState
    data class Transferring(val index: Int, val count: Int, val bytesDone: Long, val bytesTotal: Long) : TransferState
    data class Done(val count: Int) : TransferState
    /** [completed] items were fully saved or sent before the failure and are kept. */
    data class Failed(val message: String, val completed: Int = 0) : TransferState
}
