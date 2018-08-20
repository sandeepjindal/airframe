/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.json

import wvlet.log.LogSupport

import scala.annotation.{switch, tailrec}

object JSONToken {

  @inline final val LBracket = '{'
  @inline final val RBracket = '}'
  @inline final val Comma    = ','

  @inline final val DoubleQuote = '"'
  @inline final val Colon       = ':'

  @inline final val Minus = '-'
  @inline final val Plus  = '+'
  @inline final val Dot   = '.'
  @inline final val Exp   = 'e'
  @inline final val ExpL  = 'E'

  @inline final val LSquare = '['
  @inline final val RSquare = ']'

  @inline final val WS   = ' '
  @inline final val WS_T = '\t'
  @inline final val WS_N = '\n'
  @inline final val WS_R = '\r'

  @inline final val Slash     = '/'
  @inline final val BackSlash = '\\'
}

object JSONScanner {
  @inline final val DATA         = 1
  @inline final val OBJECT_START = 2
  @inline final val OBJECT_KEY   = 3
  @inline final val OBJECT_END   = 4
  @inline final val ARRAY_START  = 5
  @inline final val ARRAY_END    = 6
  @inline final val SEPARATOR    = 7

  sealed trait ScanContext {
    def isObject: Boolean
    def endState: Int
    def start: Int
    def addValue: Unit
    def numElems: Int
  }
  case class InObject(start: Int) extends ScanContext {
    private var count              = 0
    override def isObject: Boolean = true
    override def endState: Int     = OBJECT_END
    override def numElems: Int     = count
    override def addValue: Unit = {
      count += 1
    }
  }
  case class InArray(start: Int) extends ScanContext {
    private var count              = 0
    override def isObject: Boolean = false
    override def endState: Int     = ARRAY_END
    override def numElems: Int     = count
    override def addValue: Unit = {
      count += 1
    }
  }

  def scan(s: JSONSource, handler: JSONEventHandler): Unit = {
    val scanner = new JSONScanner(s, handler)
    scanner.scan
  }

  // 2-bit vector of utf8 character length table
  // Using the first 4-bits of an utf8 string
  private[json] final val utf8CharLenTable: Long = {
    var l: Long = 0L
    for (i <- 0 until 16) {
      val code = i << 4
      val len  = utf8CharLen(code)
      if (len > 0) {
        l = l | (len << (i * 2))
      }
    }
    l
  }
  // Check the valid utf8 by using the first 5-bits of utf8 string
  private[json] final val validUtf8BitVector: Long = {
    var l: Long = 0L
    for (i <- 0 until 32) {
      val code = i << 3
      val len  = utf8CharLen(code)
      if ((len == 0 && code >= 0x20) || len >= 0) {
        l = l | (1L << i)
      }
    }
    l
  }

  private[json] final val whiteSpaceBitVector: Array[Long] = {
    var b = new Array[Long](256 / 64)
    for (i <- 0 until 256) {
      import JSONToken._
      i match {
        case WS | WS_T | WS_R | WS_N =>
          b(i / 64) |= (1L << (i % 64))
        case _ =>
      }
    }
    b
  }

  private def utf8CharLen(code: Int): Int = {
    val i = code & 0xFF
    if ((i & 0x80) == 0) {
      // 0xxxxxxx
      0
    } else if ((i & 0xE0) == 0xC0) {
      // 110xxxxx
      1
    } else if ((i & 0xF0) == 0xE0) {
      // 1110xxxx
      2
    } else if ((i & 0xF8) == 0xF0) {
      3
    } else {
      -1
    }
  }
}

class JSONScanner(s: JSONSource, eventHandler: JSONEventHandler) extends LogSupport {
  private var cursor: Int       = 0
  private var lineStartPos: Int = 0
  private var line: Int         = 0

  import JSONToken._
  import JSONScanner._

  private def skipWhiteSpaces: Unit = {
    var toContinue = true
    while (toContinue) {
      val ch = s(cursor)
      (ch: @switch) match {
        case WS | WS_T | WS_R =>
          cursor += 1
        case WS_N =>
          cursor += 1
          line += 1
          lineStartPos = cursor
        case _ =>
          toContinue = false
      }
    }
  }

  private def unexpected(expected: String): Exception = {
    val char = s(cursor)
    new UnexpectedToken(line,
                        cursor - lineStartPos,
                        cursor,
                        f"Found '${String.valueOf(char.toChar)}' 0x${char}%02x. expected: ${expected}")
  }

  def scan: Unit = {
    try {
      skipWhiteSpaces
      eventHandler.startJson(s, cursor)
      s(cursor) match {
        case LBracket =>
          scanObject(Nil)
        case LSquare =>
          scanArray(Nil)
        case other =>
          throw unexpected("object or array")
      }
      eventHandler.endJson(s, cursor)
    } catch {
      case e: ArrayIndexOutOfBoundsException =>
        throw new UnexpectedEOF(line, cursor - lineStartPos, cursor, s"Unexpected EOF")
    }
  }

  final def scanObject(stack: List[ScanContext]): Unit = {
    cursor += 1
    eventHandler.startObject(s, cursor - 1)
    rscan(OBJECT_START, InObject(cursor - 1) :: stack)
  }
  final def scanArray(stack: List[ScanContext]): Unit = {
    cursor += 1
    eventHandler.startArray(s, cursor - 1)
    rscan(ARRAY_START, InArray(cursor - 1) :: stack)
  }

  final def scanValue: Unit = {
    (s(cursor): @switch) match {
      case WS | WS_T | WS_R =>
        cursor += 1
        scanValue
      case WS_N =>
        cursor += 1
        line += 1
        lineStartPos = cursor
        scanValue
      case DoubleQuote =>
        scanString
      case LBracket =>
        scanObject(Nil)
      case LSquare =>
        scanArray(Nil)
      case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
        scanNumber
      case 't' =>
        scanTrue
      case 'f' =>
        scanFalse
      case 'n' =>
        scanNull
      case _ =>
        throw unexpected("object or array")
    }
  }

  @tailrec
  private final def rscan(state: Int, stack: List[ScanContext]) {
    var ch = s(cursor)
    if (ch == WS_N) {
      cursor += 1
      line += 1
      lineStartPos = cursor
      rscan(state, stack)
    } else if (ch == WS || ch == WS_T || ch == WS_R) {
      cursor += 1
      rscan(state, stack)
    } else if (state == DATA) {
      if (ch == LSquare) {
        scanArray(stack)
      } else if (ch == LBracket) {
        scanObject(stack)
      } else {
        val ctx = stack.head
        if ((ch >= '0' && ch <= '9') || ch == '-') {
          scanNumber
          ctx.addValue
          rscan(ctx.endState, stack)
        } else if (ch == DoubleQuote) {
          scanString
          ctx.addValue
          rscan(ctx.endState, stack)
        } else if (ch == 't') {
          scanTrue
          ctx.addValue
          rscan(ctx.endState, stack)
        } else if (ch == 'f') {
          scanFalse
          ctx.addValue
          rscan(ctx.endState, stack)
        } else if (ch == 'n') {
          scanNull
          ctx.addValue
          rscan(ctx.endState, stack)
        } else {
          throw unexpected("json value")
        }
      }
    } else if ((ch == RSquare && (state == ARRAY_END || state == ARRAY_START)) ||
               (ch == RBracket && (state == OBJECT_END || state == OBJECT_START))) {
      if (stack.isEmpty) {
        throw unexpected("obj or array")
      } else {
        val ctx1 = stack.head
        val tail = stack.tail

        if (ctx1.isObject) {
          eventHandler.endObject(s, ctx1.start, cursor, ctx1.numElems)
        } else {
          eventHandler.endArray(s, ctx1.start, cursor, ctx1.numElems)
        }
        cursor += 1
        if (tail.isEmpty) {
          // root context
        } else {
          val ctx2 = tail.head
          ctx2.addValue
          rscan(ctx2.endState, tail)
        }
      }
    } else if (state == OBJECT_KEY) {
      if (ch == DoubleQuote) {
        scanString
        rscan(SEPARATOR, stack)
      } else {
        throw unexpected("DoubleQuote")
      }
    } else if (state == SEPARATOR) {
      if (ch == Colon) {
        cursor += 1
        rscan(DATA, stack)
      } else {
        throw unexpected("Colon")
      }
    } else if (state == ARRAY_END) {
      if (ch == Comma) {
        cursor += 1
        rscan(DATA, stack)
      } else {
        throw unexpected("RSquare or comma")
      }
    } else if (state == OBJECT_END) {
      if (ch == Comma) {
        cursor += 1
        rscan(OBJECT_KEY, stack)
      } else {
        throw unexpected("RBracket or comma")
      }
    } else if (state == ARRAY_START) {
      rscan(DATA, stack)
    } else {
      rscan(OBJECT_KEY, stack)
    }
  }

  private def scanNumber: Unit = {
    val numberStart = cursor

    var ch = s(cursor)
    if (ch == Minus) {
      cursor += 1
      ch = s(cursor)
    }

    if (ch == '0') {
      cursor += 1
      ch = s(cursor)
    } else if ('1' <= ch && ch <= '9') {
      while ('0' <= ch && ch <= '9') {
        cursor += 1
        ch = s(cursor)
      }
    } else {
      throw unexpected("digits")
    }

    // frac
    var dotIndex = -1
    if (ch == '.') {
      dotIndex = cursor
      cursor += 1
      ch = s(cursor)
      if ('0' <= ch && ch <= '9') {
        while ('0' <= ch && ch <= '9') {
          cursor += 1
          ch = s(cursor)
        }
      } else {
        throw unexpected("digist")
      }
    }

    // exp
    var expIndex = -1
    if (ch == Exp || ch == ExpL) {
      expIndex = cursor
      cursor += 1
      ch = s(cursor)
      if (ch == Plus | ch == Minus) {
        cursor += 1
        ch = s(cursor)
      }
      if ('0' <= ch && ch <= '9') {
        while ('0' <= ch && ch <= '9') {
          cursor += 1
          ch = s(cursor)
        }
      } else {
        throw unexpected("digits")
      }
    }

    val numberEnd = cursor
    if (numberStart < numberEnd) {
      eventHandler.numberValue(s, numberStart, numberEnd, dotIndex, expIndex)
    }
  }

  private def ensure(length: Int): Unit = {
    if (cursor + length >= s.length) {
      throw new UnexpectedEOF(line,
                              cursor - lineStartPos,
                              cursor,
                              s"Expected having ${length} characters, but ${s.length - cursor} is left")
    }
  }

  private def scanTrue: Unit = {
    ensure(4)
    if (s(cursor) == 't' && s(cursor + 1) == 'r' && s(cursor + 2) == 'u' && s(cursor + 3) == 'e') {
      cursor += 4
      eventHandler.booleanValue(s, true, cursor - 4, cursor)
    } else {
      throw unexpected("true")
    }
  }

  private def scanFalse: Unit = {
    ensure(5)
    if (s(cursor) == 'f' && s(cursor + 1) == 'a' && s(cursor + 2) == 'l' && s(cursor + 3) == 's' && s(cursor + 4) == 'e') {
      cursor += 5
      eventHandler.booleanValue(s, false, cursor - 5, cursor)
    } else {
      throw unexpected("false")
    }
  }

  private def scanNull: Unit = {
    ensure(4)
    if (s(cursor) == 'n' && s(cursor + 1) == 'u' && s(cursor + 2) == 'l' && s(cursor + 3) == 'l') {
      cursor += 4
      eventHandler.nullValue(s, cursor - 4, cursor)
    } else {
      throw unexpected("null")
    }
  }

  private final def scanSimpleString: Int = {
    var i  = 0
    var ch = s(cursor + i) & 0xFF
    while (ch != DoubleQuote) {
      if (ch < 0x20) {
        throw unexpected("utf8")
      }
      if (ch == BackSlash) {
        return -1
      }
      i += 1
      ch = s(cursor + i) & 0xFF
    }
    i
  }

  private final def scanString: Unit = {
    cursor += 1
    val stringStart = cursor
    val k           = scanSimpleString
    if (k != -1) {
      cursor += k + 1
      eventHandler.stringValue(s, stringStart, cursor - 1)
      return
    }

    var continue = true
    while (continue) {
      val ch = s(cursor)
      (ch: @switch) match {
        case DoubleQuote =>
          cursor += 1
          continue = false
        case BackSlash =>
          scanEscape
        case _ =>
          scanUtf8
      }
    }
    eventHandler.stringValue(s, stringStart, cursor - 1)
  }

//  def scanUtf8_slow: Unit = {
//    // utf-8: 0020 ... 10ffff
//    val ch = s(cursor)
//    val b1 = ch & 0xFF
//    if ((b1 & 0x80) == 0 && ch >= 0x20) {
//      // 0xxxxxxx
//      cursor += 1
//    } else if ((b1 & 0xE0) == 0xC0) {
//      // 110xxxxx
//      cursor += 1
//      scanUtf8Body(1)
//    } else if ((b1 & 0xF0) == 0xE0) {
//      // 1110xxxx
//      cursor += 1
//      scanUtf8Body(2)
//    } else if ((b1 & 0xF8) == 0xF0) {
//      // 11110xxx
//      cursor += 1
//      scanUtf8Body(3)
//    } else {
//      throw unexpected("utf8")
//    }
//  }

  def scanUtf8: Unit = {
    val ch                = s(cursor)
    val first5bit         = (ch & 0xF8) >> 3
    val isValidUtf8Header = (validUtf8BitVector & (1L << first5bit))
    if (isValidUtf8Header != 0L) {
      val pos     = (ch & 0xF0) >> (4 - 1)
      val mask    = 0x03L << pos
      val utf8len = (utf8CharLenTable & mask) >> pos
      cursor += 1
      scanUtf8Body(utf8len.toInt)
    } else {
      throw unexpected("utf8")
    }
  }

  @tailrec
  private def scanUtf8Body(n: Int): Unit = {
    if (n > 0) {
      val ch = s(cursor)
      val b  = ch & 0xFF
      if ((b & 0xC0) == 0x80) {
        // 10xxxxxx
        cursor += 1
        scanUtf8Body(n - 1)
      } else {
        throw unexpected("utf8 body")
      }
    }
  }

  private def scanEscape: Unit = {
    cursor += 1
    (s(cursor): @switch) match {
      case DoubleQuote | BackSlash | Slash | 'b' | 'f' | 'n' | 'r' | 't' =>
        cursor += 1
      case 'u' =>
        cursor += 1
        scanHex(4)
      case _ =>
        throw unexpected("escape")
    }
  }

  @tailrec
  private def scanHex(n: Int): Unit = {
    if (n > 0) {
      val ch = s(cursor)
      if ((ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F') || (ch >= '0' && ch <= '9')) {
        // OK
        cursor += 1
        scanHex(n - 1)
      } else {
        throw unexpected("hex")
      }
    }
  }
}