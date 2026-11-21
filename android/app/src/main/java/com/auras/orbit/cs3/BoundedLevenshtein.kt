/*
 * MIT License
 *
 * Copyright (c) 2026 Konstantin Tskhovrebov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.auras.orbit.cs3

import kotlin.math.round

/**
 * Bounded port of the pinned partial-ratio algorithm. The edit-op and matching-block
 * steps follow CloudStream's MIT-licensed Levenshtein implementation, attributed below.
 * No scoring string may exceed [maxInputChars]. The caller shares the one-element matrix
 * and score budgets across a whole mirror pass; null means the pass must fail closed.
 *
 * Derived from FuzzyKot commit f794d43:
 * https://github.com/terrakok/FuzzyKot/blob/f794d43/fuzzykot/src/commonMain/kotlin/com/github/terrakok/fuzzykot/Levenshtein.kt
 */
object BoundedLevenshtein {
    private enum class EditType {
        DELETE,
        EQUAL,
        INSERT,
        REPLACE,
        KEEP
    }

    private data class EditOp(
        var type: EditType? = null,
        var spos: Int = 0,
        var dpos: Int = 0
    )

    private data class MatchingBlock(
        val spos: Int = 0,
        val dpos: Int = 0,
        val length: Int = 0
    )

    private class WorkLimitExceeded : RuntimeException()

    /**
     * Returns the pinned partialRatio score, or null if a shared work budget is exhausted.
     * Both budget arrays must have length one. They are decremented before any corresponding
     * matrix/score allocation or work begins.
     */
    @JvmStatic
    fun partialRatio(
        s1: String,
        s2: String,
        maxInputChars: Int,
        maxMatrixCellsPerCall: Long,
        remainingMatrixCells: LongArray,
        remainingScoreCells: LongArray
    ): Int? {
        if (maxInputChars < 0 || s1.length > maxInputChars || s2.length > maxInputChars ||
            remainingMatrixCells.size != 1 || remainingScoreCells.size != 1 ||
            remainingMatrixCells[0] < 0L || remainingScoreCells[0] < 0L) return null

        return try {
            partialRatioBounded(
                s1,
                s2,
                maxMatrixCellsPerCall,
                remainingMatrixCells,
                remainingScoreCells
            )
        } catch (_: WorkLimitExceeded) {
            null
        }
    }

    private fun partialRatioBounded(
        p1: String,
        p2: String,
        maxMatrixCellsPerCall: Long,
        remainingMatrixCells: LongArray,
        remainingScoreCells: LongArray
    ): Int {
        val shorter: String
        val longer: String

        if (p1.length < p2.length) {
            shorter = p1
            longer = p2
        } else {
            shorter = p2
            longer = p1
        }

        if (shorter.isEmpty()) return if (longer.isEmpty()) 100 else 0

        val matchingBlocks = getMatchingBlocks(
            shorter.length,
            longer.length,
            getEditOps(shorter, longer, maxMatrixCellsPerCall, remainingMatrixCells)
        )
        var bestRatio = 0.0

        for (matchingBlock in matchingBlocks) {
            val distance = matchingBlock.dpos - matchingBlock.spos
            val longStart = if (distance > 0) distance else 0
            var longEnd = longStart + shorter.length
            if (longEnd > longer.length) longEnd = longer.length

            val longSubstring = longer.substring(longStart, longEnd)
            val ratio = basicRatio(shorter, longSubstring, remainingScoreCells)
            if (ratio > .995) return 100
            if (ratio > bestRatio) bestRatio = ratio
        }

        // kotlin.math.round uses ties-to-even, matching the pinned Kotlin implementation.
        return round(100.0 * bestRatio).toInt()
    }

    private fun basicRatio(s1: String, s2: String, remainingScoreCells: LongArray): Double {
        val lengthSum = s1.length + s2.length
        if (lengthSum == 0) return 1.0
        val editDistance = levEditDistance(s1, s2, remainingScoreCells)
        return (lengthSum - editDistance) / lengthSum.toDouble()
    }

    /** Pinned getEditOps ordering, with a pre-allocation matrix budget check. */
    private fun getEditOps(
        s1: String,
        s2: String,
        maxMatrixCellsPerCall: Long,
        remainingMatrixCells: LongArray
    ): Array<EditOp> {
        var len1Copy = s1.length
        var len2Copy = s2.length
        var len1Original = 0

        val c1 = s1
        val c2 = s2
        var p1 = 0
        var p2 = 0

        while (len1Copy > 0 && len2Copy > 0 && c1[p1] == c2[p2]) {
            len1Copy--
            len2Copy--
            p1++
            p2++
            len1Original++
        }

        val len2Original = len1Original

        while (len1Copy > 0 && len2Copy > 0 &&
            c1[p1 + len1Copy - 1] == c2[p2 + len2Copy - 1]) {
            len1Copy--
            len2Copy--
        }

        len1Copy++
        len2Copy++
        val matrixCells = len1Copy.toLong() * len2Copy.toLong()
        if (maxMatrixCellsPerCall < 0L || matrixCells > maxMatrixCellsPerCall ||
            matrixCells > remainingMatrixCells[0] || matrixCells > Int.MAX_VALUE) {
            throw WorkLimitExceeded()
        }
        remainingMatrixCells[0] -= matrixCells
        val matrix = IntArray(matrixCells.toInt())

        var index = 0
        while (index < len2Copy) {
            matrix[index] = index
            index++
        }
        index = 1
        while (index < len1Copy) {
            matrix[len2Copy * index] = index
            index++
        }

        index = 1
        while (index < len1Copy) {
            var previousPointer = (index - 1) * len2Copy
            var currentPointer = index * len2Copy
            val endPointer = currentPointer + len2Copy - 1
            val char1 = c1[p1 + index - 1]
            var char2Pointer = p2
            var cost = index
            currentPointer++

            while (currentPointer <= endPointer) {
                var candidate = matrix[previousPointer++] +
                    if (char1 != c2[char2Pointer++]) 1 else 0
                cost++
                if (cost > candidate) cost = candidate
                candidate = matrix[previousPointer] + 1
                if (cost > candidate) cost = candidate
                matrix[currentPointer++] = cost
            }
            index++
        }

        return editOpsFromCostMatrix(
            len1Copy,
            c1,
            p1,
            len1Original,
            len2Copy,
            c2,
            p2,
            len2Original,
            matrix
        )
    }

    private fun editOpsFromCostMatrix(
        len1: Int,
        c1: String,
        p1: Int,
        original1: Int,
        len2: Int,
        c2: String,
        p2: Int,
        original2: Int,
        matrix: IntArray
    ): Array<EditOp> {
        var i = len1 - 1
        var j = len2 - 1
        var position = matrix[len1 * len2 - 1]
        var pointer = len1 * len2 - 1
        val operations: Array<EditOp?> = arrayOfNulls(position)
        var direction = 0

        while (i > 0 || j > 0) {
            if (direction < 0 && j != 0 && matrix[pointer] == matrix[pointer - 1] + 1) {
                val operation = EditOp()
                position--
                operations[position] = operation
                operation.type = EditType.INSERT
                operation.spos = i + original1
                operation.dpos = --j + original2
                pointer--
                continue
            }

            if (direction > 0 && i != 0 && matrix[pointer] == matrix[pointer - len2] + 1) {
                val operation = EditOp()
                position--
                operations[position] = operation
                operation.type = EditType.DELETE
                operation.spos = --i + original1
                operation.dpos = j + original2
                pointer -= len2
                continue
            }

            if (i != 0 && j != 0 &&
                matrix[pointer] == matrix[pointer - len2 - 1] &&
                c1[p1 + i - 1] == c2[p2 + j - 1]) {
                i--
                j--
                pointer -= len2 + 1
                direction = 0
                continue
            }

            if (i != 0 && j != 0 && matrix[pointer] == matrix[pointer - len2 - 1] + 1) {
                position--
                val operation = EditOp()
                operations[position] = operation
                operation.type = EditType.REPLACE
                operation.spos = --i + original1
                operation.dpos = --j + original2
                pointer -= len2 + 1
                direction = 0
                continue
            }

            if (direction == 0 && j != 0 && matrix[pointer] == matrix[pointer - 1] + 1) {
                position--
                val operation = EditOp()
                operations[position] = operation
                operation.type = EditType.INSERT
                operation.spos = i + original1
                operation.dpos = --j + original2
                pointer--
                direction = -1
                continue
            }

            if (direction == 0 && i != 0 && matrix[pointer] == matrix[pointer - len2] + 1) {
                position--
                val operation = EditOp()
                operations[position] = operation
                operation.type = EditType.DELETE
                operation.spos = --i + original1
                operation.dpos = j + original2
                pointer -= len2
                direction = 1
            }
        }

        return operations.requireNoNulls()
    }

    private fun getMatchingBlocks(
        len1: Int,
        len2: Int,
        operations: Array<EditOp>
    ): Array<MatchingBlock> {
        val operationCount = operations.size
        var blockCount = 0
        var i: Int
        var sourcePosition: Int
        var destinationPosition: Int
        var operationIndex = 0

        destinationPosition = 0
        sourcePosition = destinationPosition
        i = operationCount
        while (i != 0) {
            while (operations[operationIndex].type === EditType.KEEP && --i != 0) {
                operationIndex++
            }
            if (i == 0) break
            if (sourcePosition < operations[operationIndex].spos ||
                destinationPosition < operations[operationIndex].dpos) {
                blockCount++
                sourcePosition = operations[operationIndex].spos
                destinationPosition = operations[operationIndex].dpos
            }
            when (operations[operationIndex].type!!) {
                EditType.REPLACE -> do {
                    sourcePosition++
                    destinationPosition++
                    i--
                    operationIndex++
                } while (i != 0 && operations[operationIndex].type === EditType.REPLACE &&
                    sourcePosition == operations[operationIndex].spos &&
                    destinationPosition == operations[operationIndex].dpos)

                EditType.DELETE -> do {
                    sourcePosition++
                    i--
                    operationIndex++
                } while (i != 0 && operations[operationIndex].type === EditType.DELETE &&
                    sourcePosition == operations[operationIndex].spos &&
                    destinationPosition == operations[operationIndex].dpos)

                EditType.INSERT -> do {
                    destinationPosition++
                    i--
                    operationIndex++
                } while (i != 0 && operations[operationIndex].type === EditType.INSERT &&
                    sourcePosition == operations[operationIndex].spos &&
                    destinationPosition == operations[operationIndex].dpos)

                else -> Unit
            }
        }

        if (sourcePosition < len1 || destinationPosition < len2) blockCount++

        val matchingBlocks = arrayOfNulls<MatchingBlock>(blockCount + 1)
        operationIndex = 0
        destinationPosition = 0
        sourcePosition = destinationPosition
        var matchingBlockIndex = 0
        i = operationCount

        while (i != 0) {
            while (operations[operationIndex].type === EditType.KEEP && --i != 0) {
                operationIndex++
            }
            if (i == 0) break
            if (sourcePosition < operations[operationIndex].spos ||
                destinationPosition < operations[operationIndex].dpos) {
                matchingBlocks[matchingBlockIndex++] = MatchingBlock(
                    spos = sourcePosition,
                    dpos = destinationPosition,
                    length = operations[operationIndex].spos - sourcePosition
                )
                sourcePosition = operations[operationIndex].spos
                destinationPosition = operations[operationIndex].dpos
            }
            when (operations[operationIndex].type!!) {
                EditType.REPLACE -> do {
                    sourcePosition++
                    destinationPosition++
                    i--
                    operationIndex++
                } while (i != 0 && operations[operationIndex].type === EditType.REPLACE &&
                    sourcePosition == operations[operationIndex].spos &&
                    destinationPosition == operations[operationIndex].dpos)

                EditType.DELETE -> do {
                    sourcePosition++
                    i--
                    operationIndex++
                } while (i != 0 && operations[operationIndex].type === EditType.DELETE &&
                    sourcePosition == operations[operationIndex].spos &&
                    destinationPosition == operations[operationIndex].dpos)

                EditType.INSERT -> do {
                    destinationPosition++
                    i--
                    operationIndex++
                } while (i != 0 && operations[operationIndex].type === EditType.INSERT &&
                    sourcePosition == operations[operationIndex].spos &&
                    destinationPosition == operations[operationIndex].dpos)

                else -> Unit
            }
        }

        if (sourcePosition < len1 || destinationPosition < len2) {
            matchingBlocks[matchingBlockIndex++] = MatchingBlock(
                spos = sourcePosition,
                dpos = destinationPosition,
                length = len1 - sourcePosition
            )
        }

        matchingBlocks[matchingBlockIndex] = MatchingBlock(
            spos = len1,
            dpos = len2,
            length = 0
        )
        return matchingBlocks.filterNotNull().toTypedArray()
    }

    /** Standard xcost=1 Levenshtein distance using one bounded row. */
    private fun levEditDistance(s1: String, s2: String, remainingScoreCells: LongArray): Int {
        var c1 = s1
        var c2 = s2
        var start1 = 0
        var start2 = 0
        var len1 = s1.length
        var len2 = s2.length

        while (len1 > 0 && len2 > 0 && c1[start1] == c2[start2]) {
            len1--
            len2--
            start1++
            start2++
        }
        while (len1 > 0 && len2 > 0 &&
            c1[start1 + len1 - 1] == c2[start2 + len2 - 1]) {
            len1--
            len2--
        }
        if (len1 == 0) return len2
        if (len2 == 0) return len1

        if (len1 > len2) {
            val oldC1 = c1
            val oldStart1 = start1
            val oldLen1 = len1
            c1 = c2
            start1 = start2
            len1 = len2
            c2 = oldC1
            start2 = oldStart1
            len2 = oldLen1
        }

        if (len1 == 1) {
            reserveScoreCells(len2.toLong(), remainingScoreCells)
            val needle = c1[start1]
            for (index in 0 until len2) {
                if (needle == c2[start2 + index]) return len2 - 1
            }
            return len2
        }

        val scoreCells = len1.toLong() * len2.toLong()
        reserveScoreCells(scoreCells, remainingScoreCells)
        val row = IntArray(len1 + 1)
        for (index in 0..len1) row[index] = index

        for (rowIndex in 1..len2) {
            var diagonal = row[0]
            row[0] = rowIndex
            for (column in 1..len1) {
                val above = row[column]
                val substitution = diagonal +
                    if (c1[start1 + column - 1] == c2[start2 + rowIndex - 1]) 0 else 1
                row[column] = minOf(substitution, above + 1, row[column - 1] + 1)
                diagonal = above
            }
        }
        return row[len1]
    }

    private fun reserveScoreCells(cells: Long, remainingScoreCells: LongArray) {
        if (cells < 0L || cells > remainingScoreCells[0]) throw WorkLimitExceeded()
        remainingScoreCells[0] -= cells
    }
}
