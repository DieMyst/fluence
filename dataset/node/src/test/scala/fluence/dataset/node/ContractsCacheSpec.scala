package fluence.dataset.node

import fluence.crypto.keypair.KeyPair
import fluence.crypto.signature.{ SignatureChecker, Signer }
import fluence.dataset.BasicContract
import fluence.dataset.node.contract.ContractRecord
import fluence.kad.protocol.Key
import fluence.storage.{ KVStore, TrieMapKVStore }
import monix.eval.Coeval
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._

class ContractsCacheSpec extends WordSpec with Matchers {

  def unsafeKey(str: String): Key = Key.fromString[Coeval](str).value

  val nodeId: Key = unsafeKey("node id")
  val nodeSigner = offerSigner("node id")

  val store: KVStore[Coeval, Key, ContractRecord[BasicContract]] =
    TrieMapKVStore()

  def offer(seed: String, participantsRequired: Int = 1): BasicContract = {
    val s = offerSigner(seed)
    BasicContract.offer(Key.fromPublicKey[Coeval](s.publicKey).value, participantsRequired, s)
  }

  def offerSigner(seed: String) = {
    new Signer.DumbSigner(KeyPair.fromBytes(seed.getBytes(), seed.getBytes()))
  }

  val cache: ContractsCache[Coeval, BasicContract] =
    new ContractsCache[Coeval, BasicContract](nodeId, store, SignatureChecker.DumbChecker, 1.minute)

  import fluence.dataset.contract.ContractWrite._

  "contracts cache" should {
    "reject caching empty and unsigned contracts" in {

      val signer = offerSigner("reject2")
      val key = Key.fromPublicKey[Coeval](signer.publicKey).value

      cache.cache(offer("reject")).value shouldBe false
      cache.cache(offer("reject2").signOffer(key, signer)).value shouldBe false

    }

    "reject caching contracts where node participates" in {
      cache.cache(offer("reject3").signOffer(nodeId, nodeSigner).sealParticipants(offerSigner("reject3"))).value shouldBe false

    }

    "cache correct contract" in {
      val v1 = offer("accept").signOffer(unsafeKey("some node"), offerSigner("some node")).sealParticipants(offerSigner("accept"))
      cache.cache(v1).value shouldBe true

      cache.find(v1.id).value shouldBe Some(v1)

      /*
      TODO: test updates when there's some data in the contract
      cache.cache(v1.copy(participants = Set(unsafeKey("another node")))).value shouldBe false

      val v2 = v1.copy(participants = Set(unsafeKey("another node")), version = 2)

      cache.cache(v2).value shouldBe true

      cache.find(v1.id).value shouldBe Some(v2)
      */

    }
  }
}
