package net.corda.core.crypto

import net.corda.core.transactions.MerkleTree
import net.corda.core.transactions.hashConcat
import java.util.*


class MerkleTreeException(val reason: String) : Exception() {
    override fun toString() = "Partial Merkle Tree exception. Reason: $reason"
}

/**
 * Building and verification of Partial Merkle Tree.
 * Partial Merkle Tree is a minimal tree needed to check that a given set of leaves belongs to a full Merkle Tree.
 *
 * Example of Merkle tree with 5 leaves.
 *
 *             h15
 *          /       \
 *         h14       h55
 *        /  \      /  \
 *      h12  h34   h5->d(h5)
 *     / \   / \   / \
 *    l1 l2 l3 l4 l5->d(l5)
 *
 * l* denote hashes of leaves, h* - hashes of nodes below.
 * h5->d(h5) denotes duplication of the left hand side node. These nodes are kept in a full tree as DuplicatedLeaf.
 * When filtering the tree for l5, we don't want to keep both l5 and its duplicate (it can also be solved using null
 * values in a tree, but this solution is clearer).
 *
 * Example of Partial tree based on the tree above.
 *
 *             ___
 *          /       \
 *          _        _
 *        /  \      /  \
 *      h12   _     _   d(h5)
 *           / \   / \
 *          I3 l4 I5 d(l5)
 *
 * We want to check l3 and l5 - now turned into IncudedLeaf (I3 and I5 above). To verify that these two leaves belong to
 * the tree with a hash root h15 we need to provide a Merkle branch (or partial tree). In our case we need hashes:
 * h12, l4, d(l5) and d(h5). Verification is done by hashing the partial tree to obtain the root and checking it against
 * the obtained h15 hash. Additionally we store included hashes used in calculation and compare them to leaves hashes we got
 * (there can be a difference in obtained leaves ordering - that's why it's a set comparison not hashing leaves into a tree).
 * If both equalities hold, we can assume that l3 and l5 belong to the transaction with root h15.
 */

class PartialMerkleTree(val root: PartialTree) {
    /**
     * The structure is a little different than that of Merkle Tree.
     * Partial Tree might not be a full binary tree. Leaves represent either original Merkle tree leaves
     * or cut subtree node with stored hash. We differentiate between the leaves that are included in a filtered
     * transaction and leaves that just keep hashes needed for calculation. Reason for this approach: during verification
     * it's easier to extract hashes used as a base for this tree.
     */
    sealed class PartialTree() {
        class IncludedLeaf(val hash: SecureHash) : PartialTree()
        class Leaf(val hash: SecureHash) : PartialTree()
        class Node(val left: PartialTree, val right: PartialTree) : PartialTree()
    }

    companion object {
        /**
         * @param merkleRoot Root of full Merkle tree.
         * @param includeHashes Hashes that should be included in a partial tree.
         * @return Partial Merkle tree root.
         */
        fun build(merkleRoot: MerkleTree, includeHashes: List<SecureHash>): PartialMerkleTree {
            val usedHashes = ArrayList<SecureHash>()
            val tree = buildPartialTree(merkleRoot, includeHashes, usedHashes)
            // Too much included hashes or different ones.
            if (includeHashes.size != usedHashes.size)
                throw MerkleTreeException("Some of the provided hashes are not in the tree.")
            return PartialMerkleTree(tree.second)
        }

        /**
         * @param root Root of full Merkle tree which is a base for a partial one.
         * @param includeHashes Hashes of leaves to be included in this partial tree.
         * @param usedHashes Hashes actually used to build this partial tree.
         * @return Pair, first element indicates if in a subtree there is a leaf that is included in that partial tree.
         * Second element refers to that subtree.
         */
        private fun buildPartialTree(
                root: MerkleTree,
                includeHashes: List<SecureHash>,
                usedHashes: MutableList<SecureHash>
        ): Pair<Boolean, PartialTree> {
            return when (root) {
                is MerkleTree.Leaf ->
                    if (root.value in includeHashes) {
                        usedHashes.add(root.value)
                        Pair(true, PartialTree.IncludedLeaf(root.value))
                    } else Pair(false, PartialTree.Leaf(root.value))
                is MerkleTree.DuplicatedLeaf -> Pair(false, PartialTree.Leaf(root.value))
                is MerkleTree.Node -> {
                    val leftNode = buildPartialTree(root.left, includeHashes, usedHashes)
                    val rightNode = buildPartialTree(root.right, includeHashes, usedHashes)
                    if (leftNode.first or rightNode.first) {
                        // This node is on a path to some included leaves. Don't store hash.
                        val newTree = PartialTree.Node(leftNode.second, rightNode.second)
                        return Pair(true, newTree)
                    } else {
                        // This node has no included leaves below. Cut the tree here and store a hash as a Leaf.
                        val newTree = PartialTree.Leaf(root.value)
                        return Pair(false, newTree)
                    }
                }
            }
        }
    }

    /**
     * @param merkleRootHash Hash that should be checked for equality with root calculated from this partial tree.
     * @param hashesToCheck List of included leaves hashes that should be found in this partial tree.
     */
    fun verify(merkleRootHash: SecureHash, hashesToCheck: List<SecureHash>): Boolean {
        val usedHashes = ArrayList<SecureHash>()
        val verifyRoot = verify(root, usedHashes)
        // It means that we obtained more/less hashes than needed or different sets of hashes.
        if (hashesToCheck.groupBy { it } != usedHashes.groupBy { it })
            return false
        return (verifyRoot == merkleRootHash)
    }

    /**
     * Recursive calculation of root of this partial tree.
     * Modifies usedHashes to later check for inclusion with hashes provided.
     */
    private fun verify(node: PartialTree, usedHashes: MutableList<SecureHash>): SecureHash {
        return when (node) {
            is PartialTree.IncludedLeaf -> {
                usedHashes.add(node.hash)
                node.hash
            }
            is PartialTree.Leaf -> node.hash
            is PartialTree.Node -> {
                val leftHash = verify(node.left, usedHashes)
                val rightHash = verify(node.right, usedHashes)
                return leftHash.hashConcat(rightHash)
            }
        }
    }
}
