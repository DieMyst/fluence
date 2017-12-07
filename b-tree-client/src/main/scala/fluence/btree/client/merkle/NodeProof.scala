package fluence.btree.client.merkle

import fluence.btree.client.common.BytesOps
import fluence.hash.CryptoHasher

/**
 * Contains all information needed for recalculating checksum of node with substituting child's checksum, for verifying
 * child's checksum or recalculating checksum of the node.
 */
trait NodeProof {

  /**
   * Calculates checksum of current ''NodeProof''.
   *
   * @param cryptoHasher Hash service uses for calculating nodes checksums.
   * @param checksumForSubstitution Child's checksum for substitution to ''childrenChecksums''
   * @return Checksum of current ''NodeProof''
   */
  def calcChecksum(
    cryptoHasher: CryptoHasher[Array[Byte], Array[Byte]],
    checksumForSubstitution: Option[Array[Byte]]
  ): Array[Byte]

}

/**
 * ''GeneralNodeProof'' contains 2 part:
 *    ''stateChecksum'' - a checksum of node state, which is not related to the checksums of node children
 *    ''childrenChecksums'' - an array of childs checksums, which participates in the substitution
 * GenerateNodeProof's checksum is a hash of ''stateChecksum'' and ''childrenChecksums''
 *
 * For leaf ''childrenChecksums'' is sequence with checksums of each 'key-value' pair of this leaf and ''substitutionIdx''
 * is index for inserting checksum of 'key-value' pair.
 *
 * For branch ''childrenChecksums'' is sequence with checksums for each children of this branch and ''substitutionIdx''
 * is index for inserting checksum of child.
 *
 * @param stateChecksum     A checksum of node state, which is not related to the checksums of children
 * @param childrenChecksums Checksums of all node children
 * @param substitutionIdx   Index for substitution some child checksum to ''childrenChecksums''
 */
case class GeneralNodeProof(
    stateChecksum: Array[Byte],
    childrenChecksums: Array[Array[Byte]],
    substitutionIdx: Int
) extends NodeProof {

  override def calcChecksum(
    cryptoHasher: CryptoHasher[Array[Byte], Array[Byte]],
    checksumForSubstitution: Option[Array[Byte]]
  ): Array[Byte] = {

    checksumForSubstitution match {
      case None ⇒
        // if checksum for substitution isn't defined just calculate node checksum
        cryptoHasher.hash(Array.concat(stateChecksum, childrenChecksums.flatten))
      case Some(checksum) ⇒
        // if checksum is defined substitute it to childsChecksum and calculate node checksum
        val updatedArray = BytesOps.rewriteValue(childrenChecksums, checksum, substitutionIdx)
        cryptoHasher.hash(Array.concat(stateChecksum, updatedArray.flatten))
    }

  }

  override def toString: String = {
    s"NodeProof(stateChecksum=${new String(stateChecksum)}," +
      s"checksums=${childrenChecksums.map(new String(_)).mkString("[", ",", "]")},subIdx=$substitutionIdx)"
  }

}
