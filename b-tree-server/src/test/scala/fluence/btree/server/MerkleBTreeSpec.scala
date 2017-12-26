package fluence.btree.server

import java.nio.ByteBuffer

import fluence.btree.client.merkle.MerkleRootCalculator
import fluence.btree.client.network._
import fluence.btree.client.{ BTreeVerifier, Key, Value }
import fluence.btree.server.binary.BTreeBinaryStore
import fluence.btree.server.network.commands.{ GetCommandImpl, PutCommandImpl }
import fluence.codec.kryo.KryoCodecs
import fluence.hash.TestCryptoHasher
import fluence.node.storage.InMemoryKVStore
import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ Matchers, WordSpec }

import scala.collection.Searching.{ Found, InsertionPoint }
import scala.concurrent.duration._
import scala.math.Ordering
import scala.util.Random

class MerkleBTreeSpec extends WordSpec with Matchers with ScalaFutures {

  implicit object BytesOrdering extends Ordering[Array[Byte]] {
    override def compare(x: Array[Byte], y: Array[Byte]): Int = ByteBuffer.wrap(x).compareTo(ByteBuffer.wrap(y))
  }

  private val Arity = 4
  private val Alpha = 0.25F
  private val Config = MerkleBTreeConfig(arity = Arity, alpha = Alpha)
  private val MinSize = (Arity * Alpha).toInt
  private val MaxSize = Arity

  private val key1: Key = "k0001".getBytes()
  private val value1: Value = "v0001".getBytes()
  private val key2: Key = "k0002".getBytes()
  private val value2: Value = "v0002".getBytes()
  private val key3: Key = "k0003".getBytes()
  private val value3: Value = "v0003".getBytes()
  private val key4: Key = "k0004".getBytes()
  private val value4: Value = "v0004".getBytes()
  private val key5: Key = "k0005".getBytes()
  private val value5: Value = "v0005".getBytes()

  val codecs = KryoCodecs()
    .add[Key]
    .add[Array[Key]]
    .add[Value]
    .add[Array[Value]]
    .add[NodeId]
    .add[Array[NodeId]]
    .add[Int]
    .add[Node]
    .addCase(classOf[Leaf])
    .addCase(classOf[Branch])
    .build[Task]()

  import codecs._

  //    val hasher = JdkCryptoHash.Sha256
  val hasher = TestCryptoHasher
  val nodeOp = NodeOps(hasher)
  val inspector = BTreeVerifier(hasher)
  private val mRootCalculator = MerkleRootCalculator(hasher)

  "put" should {
    "show error from client or network" when {
      "something wrong with sending leaf to client" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        val result = wait(Task.sequence(failedPutCmd(1 to 1, classOf[LeafResponse]) map { cmd ⇒ tree.put(cmd) }).failed)
        result.getMessage shouldBe "Client unavailable"
      }

      "something wrong with verifying changes by client" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        val result = wait(Task.sequence(failedPutCmd(1 to 1, classOf[VerifySimplePutResponse]) map { cmd ⇒ tree.put(cmd) }).failed)

        result.getMessage shouldBe "Client unavailable"

      }
    }
    "correct insert new value" when {
      "tree is empty" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(1 to 1) map { cmd ⇒ tree.put(cmd) }))

        // check tree state
        tree.getDepth shouldBe 1
        val root = wait(tree.getRoot).asInstanceOf[Leaf]
        checkLeaf(Array(key1), Array(value1), root)
      }

      "tree contains 1 element, insertion key is less than key in tree" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(2 to (1, -1)) map { cmd ⇒ tree.put(cmd) }))

        // check tree state
        tree.getDepth shouldBe 1
        val root = wait(tree.getRoot).asInstanceOf[Leaf]
        checkLeaf(Array(key1, key2), Array(value1, value2), root)
        checkNodeValidity(root)
      }

      "tree contains 1 element, insertion key is more than key in tree" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(1 to 2) map { cmd ⇒ tree.put(cmd) }))

        // check tree state
        tree.getDepth shouldBe 1
        val root = wait(tree.getRoot).asInstanceOf[Leaf]
        checkLeaf(Array(key1, key2), Array(value1, value2), root)
        checkNodeValidity(root)
      }

      "tree has filled root-leaf" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(1 to 5) map { cmd ⇒ tree.put(cmd) }))

        tree.getDepth shouldBe 2
        val root = wait(tree.getRoot).asInstanceOf[Branch]
        checkNodeValidity(root)
        checkTree(Array(key2), Array(1, 2), root)

        val rootChildren: Array[Node] = root.children.map(childId ⇒ wait(store.get(childId)))
        rootChildren should have size 2
        rootChildren.foreach(child ⇒ checkNodeValidity(child))

      }

      "many put operation with ascending keys (only leaf is spiting)" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(1 to 11) map { cmd ⇒ tree.put(cmd) }))

        val root = wait(tree.getRoot).asInstanceOf[Branch]
        checkNodeValidity(root)

        tree.getDepth shouldBe 2
        val rootChildren: Array[Node] = root.children.map(childId ⇒ wait(store.get(childId)))
        rootChildren.foldLeft(0)((acc, node) ⇒ acc + node.size) shouldBe 11
        rootChildren.foreach(child ⇒ checkNodeValidity(child))

      }

      "many put operation with descending keys (only leaf is spiting)" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(11 to (1, -1)) map { cmd ⇒ tree.put(cmd) }))

        val root = wait(tree.getRoot).asInstanceOf[Branch]
        checkNodeValidity(root)

        tree.getDepth shouldBe 2
        val rootChildren: Array[Node] = root.children.map(childId ⇒ wait(store.get(childId)))
        rootChildren.foldLeft(0)((acc, node) ⇒ acc + node.size) shouldBe 11
        rootChildren.foreach(child ⇒ checkNodeValidity(child))
      }

      "many put operation with ascending keys (leafs and trees are splitting)" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(1 to 32) map { cmd ⇒ tree.put(cmd) }))

        val root = wait(tree.getRoot).asInstanceOf[Branch]
        checkNodeValidity(root)

        tree.getDepth shouldBe 4
        val rootChildren: Array[Node] = root.children.map(childId ⇒ wait(store.get(childId)))
        rootChildren.foreach(child ⇒ checkNodeValidity(child))
      }

      "many put operation with descending keys (leafs and trees are splitting)" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(32 to (1, -1)) map { cmd ⇒ tree.put(cmd) }))

        val root = wait(tree.getRoot).asInstanceOf[Branch]
        checkNodeValidity(root)

        tree.getDepth shouldBe 3
        val rootChildren: Array[Node] = root.children.map(childId ⇒ wait(store.get(childId)))
        rootChildren.foreach(child ⇒ checkNodeValidity(child))
      }

      "many put operation with random keys (leafs and trees are splitting)" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(Random.shuffle(1 to 32)) map { cmd ⇒ tree.put(cmd) }))

        val root = wait(tree.getRoot).asInstanceOf[Branch]
        checkNodeValidity(root)

        tree.getDepth should be >= 3
        val rootChildren: Array[Node] = root.children.map(childId ⇒ wait(store.get(childId)))
        rootChildren.foreach(child ⇒ checkNodeValidity(child))
      }
    }

    "correct update value" when {
      "tree has 1 element" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(tree.put(new PutCommandImpl[Task](mRootCalculator, { _ ⇒ Task(Some(PutRequest(key1, value1, InsertionPoint(0)))) })))
        wait(tree.put(new PutCommandImpl[Task](mRootCalculator, { _ ⇒ Task(Some(PutRequest(key1, value2, Found(0)))) })))

        // check tree state
        tree.getDepth shouldBe 1
        val root = wait(tree.getRoot).asInstanceOf[Leaf]
        checkLeaf(Array(key1), Array(value2), root)
      }

      "tree has filled root-leaf" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(MerkleBTreeConfig(arity = 4), store, nodeOp)

        wait(Task.sequence(putCmd(1 to 4) map { cmd ⇒ tree.put(cmd) }))
        wait(tree.put(new PutCommandImpl[Task](mRootCalculator, { _ ⇒ Task(Some(PutRequest(key2, value5, Found(1)))) })))

        tree.getDepth shouldBe 1
        val root = wait(tree.getRoot).asInstanceOf[Leaf]
        checkLeaf(Array(key1, key2, key3, key4), Array(value1, value5, value3, value4), root)
      }
    }
  }

  "get" should {
    "show error from client or network" when {
      "something wrong with sending leaf to client" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        val result = wait(tree.get(failedGetCmd(key1, classOf[LeafResponse])).failed)
        result.getMessage shouldBe "Client unavailable"
      }

      "something wrong with searching next child" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(1 to 5) map { cmd ⇒ tree.put(cmd) }))
        val result = wait(tree.get(failedGetCmd(key1, classOf[NextChildSearchResponse])).failed)
        result.getMessage shouldBe "Client unavailable"

      }
    }

    "return empty result" when {
      "value not found" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(tree.get(getCmd(key1, { result ⇒ result shouldBe None })))
      }

      "value found in root-leaf with one value" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(1 to 1) map { cmd ⇒ tree.put(cmd) }))
        wait(tree.get(getCmd(key1, { result ⇒ result.get shouldBe value1 })))
      }

      "value found in filled root-leaf" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(1 to 4) map { cmd ⇒ tree.put(cmd) }))
        wait(tree.get(getCmd(key3, { result ⇒ result.get shouldBe value3 })))
      }

      "value found in huge tree" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(Random.shuffle(1 to 512)) map { cmd ⇒ tree.put(cmd) }))

        val minKey = "k0001".getBytes
        val midKey = "k0256".getBytes
        val maxKey = "k0512".getBytes
        val absentKey = "k2048".getBytes

        wait(tree.get(getCmd(minKey, { result ⇒ result.get shouldBe "v0001".getBytes })))
        wait(tree.get(getCmd(midKey, { result ⇒ result.get shouldBe "v0256".getBytes })))
        wait(tree.get(getCmd(maxKey, { result ⇒ result.get shouldBe "v0512".getBytes })))
        wait(tree.get(getCmd(absentKey, { result ⇒ result shouldBe None })))
      }

    }

  }

  "put and get" should {
    "save and return correct results" when {
      "put key1, get key1" in {
        implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
        val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
        val tree = new MerkleBTree(Config, store, nodeOp)

        wait(Task.sequence(putCmd(1 to 1) map { cmd ⇒ tree.put(cmd) }))
        wait(tree.get(getCmd(key1, { result ⇒ result.get shouldBe value1 })))
      }
    }

    "put many value in random order and get theirs" in {
      implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
      val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
      val tree = new MerkleBTree(MerkleBTreeConfig(arity = Arity), store, nodeOp)

      wait(Task.sequence(putCmd(Random.shuffle(1 to 1024)) map { cmd ⇒ tree.put(cmd) }))

      tree.getDepth should be >= 5

      val minKey = "k0001".getBytes
      val midKey = "k0512".getBytes
      val maxKey = "k1024".getBytes
      val absentKey = "k2048".getBytes

      wait(tree.get(getCmd(minKey, { result ⇒ result.get shouldBe "v0001".getBytes })))
      wait(tree.get(getCmd(midKey, { result ⇒ result.get shouldBe "v0512".getBytes })))
      wait(tree.get(getCmd(maxKey, { result ⇒ result.get shouldBe "v1024".getBytes })))
      wait(tree.get(getCmd(absentKey, { result ⇒ result shouldBe None })))

    }

    "put twice many value in random order and get theirs" in {
      implicit val testScheduler: TestScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
      val store = new BTreeBinaryStore[NodeId, Node, Task](InMemoryKVStore())
      val tree = new MerkleBTree(MerkleBTreeConfig(arity = Arity), store, nodeOp)

      // put 1024 elements
      wait(Task.sequence(putCmd(Random.shuffle(1 to 1024)) map { cmd ⇒ tree.put(cmd) }))

      // put 1024 elements again
      wait(Task.sequence(putCmd(Random.shuffle(1 to 1024)) map { cmd ⇒ tree.put(cmd) }))

      tree.getDepth should be >= 5

      val minKey = "k0001".getBytes
      val midKey = "k0512".getBytes
      val maxKey = "k1024".getBytes
      val absentKey = "k2048".getBytes

      wait(tree.get(getCmd(minKey, { result ⇒ result.get shouldBe "v0001".getBytes })))
      wait(tree.get(getCmd(midKey, { result ⇒ result.get shouldBe "v0512".getBytes })))
      wait(tree.get(getCmd(maxKey, { result ⇒ result.get shouldBe "v1024".getBytes })))
      wait(tree.get(getCmd(absentKey, { result ⇒ result shouldBe None })))
    }

  }

  /* util methods */

  private def wait[T](task: Task[T], time: FiniteDuration = 3.second)(implicit TS: TestScheduler): T = {
    val async = task.runAsync
    TS.tick(time)
    async.futureValue
  }

  private def checkLeaf(expKeys: Array[Key], expValues: Array[Value], node: Leaf): Unit = {
    node.keys should contain theSameElementsInOrderAs expKeys
    node.values should contain theSameElementsInOrderAs expValues
    node.size shouldBe expKeys.length
    node.checksum should not be empty
  }

  private def checkTree(expKeys: Array[Key], expChildren: Array[NodeId], tree: Branch): Unit = {
    tree.keys should contain theSameElementsInOrderAs expKeys
    tree.children should contain theSameElementsInOrderAs expChildren
    tree.size shouldBe expKeys.length
    tree.checksum should not be empty
  }

  private def checkNodeValidity(node: Node, min: Int = MinSize, max: Int = MaxSize): Unit = {
    node match {
      case tree: Branch ⇒
        checkNodeSize(tree, min, max)
        checkOrderOfKeys(tree.keys)
        tree.children.length should be >= tree.size
      case leaf: Node ⇒
        checkNodeSize(leaf, min, max)
        checkOrderOfKeys(leaf.keys)
        leaf.checksum should not be empty
    }
  }

  private def checkOrderOfKeys(keys: Array[Key]): Unit = {
    keys should have size keys.toSet.size // shouldn't be duplicates
    keys should contain theSameElementsInOrderAs keys.sorted // should be ascending order
  }

  private def checkNodeSize(node: Node, min: Int = MinSize, max: Int = MaxSize): Unit = {
    node.size shouldBe node.keys.length
    node.size should be >= min
    node.size should be <= max
    node.checksum should not be empty
  }

  /** Creates Seq of PutCommand for specified Range of key indexes. */
  private def putCmd(seq: Seq[Int]): Seq[PutCommandImpl[Task]] = {

    seq map { i ⇒
      new PutCommandImpl[Task](
        mRootCalculator, {
        case LeafResponse(keys, _) ⇒
          import scala.collection.Searching._
          Task(Some(PutRequest(f"k$i%04d".getBytes(), f"v$i%04d".getBytes(), keys.search(f"k$i%04d".getBytes()))))
        case NextChildSearchResponse(keys, _) ⇒
          import scala.collection.Searching._
          Task(Some(ResumeSearchRequest(keys.search(f"k$i%04d".getBytes()).insertionPoint)))
        case VerifySimplePutResponse(mRoot) ⇒
          Task(Some(Confirm))
        case VerifyPutWithRebalancingResponse(mPath) ⇒
          Task(Some(Confirm))
      })
    }
  }

  /**
   * Creates Seq of PutCommand for specified Range of key indexes and raise exception
   * for specified BTreeServerResponse type.
   */
  private def failedPutCmd[T <: BTreeServerResponse](
    seq: Seq[Int],
    failWhenReceive: Class[T],
    errMsg: String = "Client unavailable"
  ): Seq[PutCommandImpl[Task]] = {
    seq map { i ⇒
      new PutCommandImpl[Task](
        mRootCalculator, {
        case LeafResponse(keys, _) ⇒
          if (failWhenReceive == classOf[LeafResponse]) {
            Task.raiseError(new Exception(errMsg))
          } else {
            import scala.collection.Searching._
            Task(Some(PutRequest(f"k$i%04d".getBytes(), f"v$i%04d".getBytes(), keys.search(f"k$i%04d".getBytes()))))
          }
        case NextChildSearchResponse(keys, _) ⇒
          if (failWhenReceive == classOf[NextChildSearchResponse]) {
            Task.raiseError(new Exception(errMsg))
          } else {
            import scala.collection.Searching._
            Task(Some(ResumeSearchRequest(keys.search(f"k$i%04d".getBytes()).insertionPoint)))
          }
        case VerifySimplePutResponse(_) | VerifyPutWithRebalancingResponse(_) ⇒
          if (failWhenReceive == classOf[VerifySimplePutResponse] || failWhenReceive == classOf[VerifyPutWithRebalancingResponse]) {
            Task.raiseError(new Exception(errMsg))
          } else {
            Task(Some(Confirm))
          }
      })
    }

  }

  /** Search value for specified key and return callback for searched result */
  private def getCmd(key: Key, resultFn: Option[Value] ⇒ Unit): GetCommandImpl[Task] = {
    new GetCommandImpl[Task]({
      case NextChildSearchResponse(keys, _) ⇒
        import scala.collection.Searching._
        Task(Some(ResumeSearchRequest(keys.search(key).insertionPoint)))
      case LeafResponse(keys, values) ⇒
        import scala.collection.Searching._
        keys.search(key) match {
          case Found(i) ⇒
            resultFn(Some(values(i)))
            Task(None)
          case InsertionPoint(_) ⇒
            resultFn(None)
            Task(None)
        }
    })
  }

  /** Search value for specified key and raise exception for specified BTreeServerResponse type */
  private def failedGetCmd[T](
    key: Key,
    failWhenReceive: Class[T],
    errMsg: String = "Client unavailable"
  ): GetCommandImpl[Task] = {
    new GetCommandImpl[Task]({
      case NextChildSearchResponse(keys, _) ⇒
        if (failWhenReceive == classOf[NextChildSearchResponse]) {
          Task.raiseError(new Exception(errMsg))
        } else {
          import scala.collection.Searching._
          Task(Some(ResumeSearchRequest(keys.search(key).insertionPoint)))
        }
      case LeafResponse(keys, _) ⇒
        if (failWhenReceive == classOf[LeafResponse]) {
          Task.raiseError(new Exception(errMsg))
        } else {
          Task(None)
        }
    })
  }

}
