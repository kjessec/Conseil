package tech.cryptonomic.conseil.config

sealed trait Depth
case object Everything extends Depth
case object Newest extends Depth
case class Custom(depth: Int) extends Depth
