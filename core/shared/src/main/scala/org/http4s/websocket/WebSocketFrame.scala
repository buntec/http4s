/*
 * Copyright 2013 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.websocket

import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.hashing.MurmurHash3

abstract class WebSocketFrame {
  def opcode: Int
  def data: ByteVector
  def last: Boolean

  final def length: Int = data.length.toInt

  override def equals(obj: Any): Boolean =
    obj match {
      case wf: WebSocketFrame =>
        this.opcode == wf.opcode &&
        this.last == wf.last &&
        this.data == wf.data
      case _ => false
    }

  override def hashCode: Int = {
    var hash = WebSocketFrame.hashSeed
    hash = MurmurHash3.mix(hash, opcode.##)
    hash = MurmurHash3.mix(hash, data.##)
    hash = MurmurHash3.mixLast(hash, last.##)
    hash
  }
}

object WebSocketFrame {
  private val hashSeed = MurmurHash3.stringHash("WebSocketFrame")

  sealed abstract class ControlFrame extends WebSocketFrame {
    final def last: Boolean = true
  }

  sealed abstract class Text extends WebSocketFrame {
    def str: String
    def opcode: Int = TEXT

    override def toString: String = s"Text('$str', last: $last)"
  }

  private class BinaryText(val data: ByteVector, val last: Boolean) extends Text {
    lazy val str: String = new String(data.toArray, UTF_8)
  }

  private class StringText(override val str: String, val last: Boolean) extends Text {
    lazy val data: ByteVector = ByteVector.view(str.getBytes(UTF_8))
  }

  object Text {
    def apply(str: String, last: Boolean = true): Text = new StringText(str, last)
    def apply(data: ByteVector, last: Boolean): Text = new BinaryText(data, last)
    def apply(data: ByteVector): Text = new BinaryText(data, true)
    def unapply(txt: Text): Option[(String, Boolean)] = Some((txt.str, txt.last))
  }

  final case class Binary(data: ByteVector, last: Boolean = true) extends WebSocketFrame {
    def opcode: Int = BINARY
    override def toString: String = s"Binary(Array(${data.length}), last: $last)"
  }

  final case class Continuation(data: ByteVector, last: Boolean) extends WebSocketFrame {
    def opcode: Int = CONTINUATION
    override def toString: String = s"Continuation(Array(${data.length}), last: $last)"
  }

  final case class Ping(data: ByteVector = ByteVector.empty) extends ControlFrame {
    def opcode: Int = PING
    override def toString: String =
      if (data.length > 0) s"Ping(Array(${data.length}))"
      else s"Ping"
  }

  final case class Pong(data: ByteVector = ByteVector.empty) extends ControlFrame {
    def opcode: Int = PONG
    override def toString: String =
      if (data.length > 0) s"Pong(Array(${data.length}))"
      else s"Pong"
  }

  final case class Close(data: ByteVector = ByteVector.empty) extends ControlFrame {
    def opcode: Int = CLOSE

    def closeCode: Int =
      if (data.length > 0)
        (data(0) << 8 & 0xff00) | (data(1) & 0xff) // 16-bit unsigned
      else 1005 // No code present

    override def toString: String =
      if (data.length > 0) s"Close(Array(${data.length}))"
      else s"Close"
  }

  sealed abstract class InvalidCloseDataException extends RuntimeException
  // scalafix:off Http4sGeneralLinters.leakingSealedHierarchy; bincompat until 1.0
  class InvalidCloseCodeException(val i: Int) extends InvalidCloseDataException
  class ReasonTooLongException(val s: String) extends InvalidCloseDataException
  // scalafix:on

  private def toUnsignedShort(x: Int) = Array[Byte](((x >> 8) & 0xff).toByte, (x & 0xff).toByte)

  private def reasonToBytes(reason: String) = {
    val asBytes = ByteVector.view(reason.getBytes(UTF_8))
    if (asBytes.length > 123)
      Left(new ReasonTooLongException(reason))
    else
      Right(asBytes)
  }

  private def closeCodeToBytes(code: Int): Either[InvalidCloseCodeException, ByteVector] =
    if (code < 1000 || code > 4999) Left(new InvalidCloseCodeException(code))
    else Right(ByteVector.view(toUnsignedShort(code)))

  object Close {
    def apply(code: Int): Either[InvalidCloseDataException, Close] =
      closeCodeToBytes(code).map(Close(_))

    def apply(code: Int, reason: String): Either[InvalidCloseDataException, Close] =
      for {
        c <- closeCodeToBytes(code): Either[InvalidCloseDataException, ByteVector]
        r <- reasonToBytes(reason)
      } yield Close(c ++ r)
  }
}
