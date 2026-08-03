package tech.hearth.it.transactions

import tech.hearth.it.*
import org.scalatest.*

trait BaseTransactionSuiteLike extends IntegrationSuiteWithThreeAddresses with NodesFromDocker {
  this: TestSuite & Nodes =>

}

abstract class BaseTransactionSuite extends funsuite.AnyFunSuite with BaseTransactionSuiteLike
