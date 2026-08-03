package tech.hearth.settings

import pureconfig.*

case class RewardsVotingSettings(desired: Option[Long]) derives ConfigReader
