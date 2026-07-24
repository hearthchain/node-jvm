package com.wavesplatform.state

import cats.syntax.option.*
import com.wavesplatform.settings.{FunctionalitySettings, WavesSettings}

case class GenerationPeriod(start: Height, length: Int) extends Ordered[GenerationPeriod] {
  def end: Height = start + length - 1

  def next: GenerationPeriod = move(end + 1)

  def prev: Option[GenerationPeriod] =
    if (start == Height(1)) none
    else move(start - length).some

  def max(other: GenerationPeriod): GenerationPeriod = if (start < other.start) other else this

  private def move(newStart: Height): GenerationPeriod = GenerationPeriod(newStart, length)

  override def compare(that: GenerationPeriod): Int = start compare that.start

  override def toString: String = s"[$start, $end]"
}

object GenerationPeriod {
  def from(h: Height, wavesSettings: WavesSettings): GenerationPeriod =
    from(h, wavesSettings.blockchainSettings.functionalitySettings)

  def from(h: Height, functionalitySettings: FunctionalitySettings): GenerationPeriod =
    from(h, functionalitySettings.generationPeriodLength)

  def from(h: Height, generationPeriodLength: Int): GenerationPeriod =
    GenerationPeriod(Height(((h.toInt - 1) / generationPeriodLength) * generationPeriodLength + 1), generationPeriodLength)

  def enclosedPeriods(
      generationPeriodLength: Int,
      start: Height,
      end: Height
  ): Option[(start: GenerationPeriod, end: GenerationPeriod)] = {
    val fromGenerationPeriod = from(start, generationPeriodLength)

    if (fromGenerationPeriod.start > end) none
    else
      Some(fromGenerationPeriod, from(end, generationPeriodLength))
  }
}
