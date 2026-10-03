package com.sempermechanics.semper.util

/**
 * For progress reported as `done` of `total` finished items: which item a
 * "… k of N" label names. The item in progress is the one after the finished
 * ones, so a label fed `done` directly starts at "0 of N" and trails by one,
 * while a bar fed the in-progress number reaches 100% before the last item
 * has finished. Labels take [current]; bars take `done`.
 */
object ProgressCount {

    /** 1-based number of the item being worked on after [done] finished, capped at [total]; 0 when empty. */
    fun current(done: Int, total: Int): Int = if (total <= 0) 0 else (done + 1).coerceIn(1, total)
}
