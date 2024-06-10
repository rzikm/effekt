package effekt

import effekt.source.*

import effekt.lexer.{ Lexer, Position, Token, TokenKind }
import effekt.lexer.TokenKind.*

import kiama.util.{ Positions, StringSource }

import munit.Location

class RecursiveDescentTests extends munit.FunSuite {

  def parser(input: String, positions: Positions)(using munit.Location): RecursiveDescentParsers = {
    val lexer = effekt.lexer.Lexer(input)
    val (tokens, error) = lexer.run()
    if (error.nonEmpty) fail(s"Lexer errors: ${error}")
    new RecursiveDescentParsers(positions, tokens)
  }

  def parse[R](input: String, f: RecursiveDescentParsers => R, positions: Positions = new Positions())(using munit.Location): R =
    try {
      val p = parser(input, positions)
      val result = f(p)
      assert(p.peek(TokenKind.EOF), s"Did not consume everything: ${p.peek}")
      result
    } catch {
      case ParseError2(msg, pos) =>
        fail(s"Unexpected parse error (token index ${pos}): ${msg}")
    }

  def parseExpr(input: String, positions: Positions = new Positions())(using munit.Location): Term =
    parse(input, _.expr())

  def parseStmt(input: String, positions: Positions = new Positions())(using munit.Location): Stmt =
    parse(input, _.stmt())

  def parseStmts(input: String, positions: Positions = new Positions())(using munit.Location): Stmt =
    parse(input, _.stmts())

  def parseMatchPattern(input: String, positions: Positions = new Positions())(using munit.Location): MatchPattern =
    parse(input, _.matchPattern())

  def parseMatchClause(input: String, positions: Positions = new Positions())(using munit.Location): MatchClause =
    parse(input, _.matchClause())

  def parseValueType(input: String, positions: Positions = new Positions())(using munit.Location): ValueType =
    parse(input, _.valueType())

  def parseBlockType(input: String, positions: Positions = new Positions())(using munit.Location): BlockType =
    parse(input, _.blockType())

  def parseOpClause(input: String, positions: Positions = new Positions())(using munit.Location): OpClause =
    parse(input, _.opClause())

  def parseImplementation(input: String, positions: Positions = new Positions())(using munit.Location): Implementation =
    parse(input, _.implementation())

  def parseTry(input: String, positions: Positions = new Positions())(using munit.Location): Term =
    parse(input, _.tryExpr())

  def parseParams(input: String, positions: Positions = new Positions())(using munit.Location): (List[Id], List[ValueParam], List[BlockParam]) =
    parse(input, _.params())

  def parseDefinition(input: String, positions: Positions = new Positions())(using munit.Location): Def =
    parse(input, _.definition())

  def parseDefinitions(input: String, positions: Positions = new Positions())(using munit.Location): List[Def] =
    parse(input, _.definitions())

  test("Peeking") {
    implicit def toToken(t: TokenKind): Token = Token(0, 0, t)
    def peek(tokens: Seq[Token], offset: Int): Token =
      new RecursiveDescentParsers(new Positions, tokens).peek(offset)

    val tokens = List[Token](`(`, Space, Newline, `)`, Space, `=>`, EOF)
    assertEquals(peek(tokens, 0).kind, `(`)
    assertEquals(peek(tokens, 1).kind, `)`)
    assertEquals(peek(tokens, 2).kind, `=>`)
    assertEquals(peek(tokens, 3).kind, EOF)
  }

  test("Simple expressions") {
    parseExpr("42")
    parseExpr("f")
    parseExpr("f(a)")
    parseExpr("f(a, 42)")

    assertNotEquals(
      parseExpr("f.m(a, 42)"),
      parseExpr("(f.m)(a, 42)"))

    assertEquals(
      parseExpr("f(a, 42)()"),
      parseExpr("(f(a, 42))()"))
  }

  test("boxing") {
    parseExpr("box f")
    parseExpr("unbox f")
    assertEquals(
      parseExpr("unbox box f"),
      parseExpr("unbox (box f)")
    )
    assertNotEquals(
      parseExpr("box { 42 }"),
      parseExpr("box {} { 42 }")
    )
    parseExpr("box { (x: Int) => x }")

    // { f } is parsed as a capture set and not backtracked.
    intercept[Throwable] { parseExpr("box { f }") }
  }

  test("Qualified names") {
    assertEquals(parseExpr("map"), Var(IdRef(List(), "map")))
    assertEquals(parseExpr("list::map"), Var(IdRef(List("list"), "map")))
    assertEquals(parseExpr("list::internal::test"), Var(IdRef(List("list", "internal"), "test")))
  }

  test("Operator precedence") {
    parseExpr("1 + 1")

    assertEquals(
      parseExpr("1 + 2 + 3"),
      parseExpr("(1 + 2) + 3"))

    assertEquals(
      parseExpr("1 + 2 * 3"),
      parseExpr("1 + (2 * 3)"))

    assertEquals(
      parseExpr("1 + 2 * 3 == 4 + 5"),
      parseExpr("(1 + (2 * 3)) == (4 + 5)"))
  }

  test("Dangling else") {
    assertEquals(
      parseExpr("if (42) if (42) x else y"),
      parseExpr("if (42) (if (42) x else y) else ()"))
  }

  test("Simple statements") {
    parseStmt("42")
    parseStmt("return 42")
    parseStmt("{ f(); return 43 }")
    parseStmt("{ f(); 43 }")
  }

  test("Compound statements") {
    parseStmts(
      """ val x = { 42; 43 };
        | val y = f(x);
        | y
        |""".stripMargin)

    parseStmts(
      """with foo().baz;
        |bar()
        |""".stripMargin)

    parseStmts(
      """var x = baz;
        |return x
        |""".stripMargin)

    parseStmts(
      """var x in r = baz;
        |return x
        |""".stripMargin)
  }

  test("Definition statements") {
    parseStmts(
      """val x = 42
        |type T = Int
        |()
        |""".stripMargin)
  }

  test("Semicolon insertion") {
    parseStmts("f(); return x")
    parseStmts(
      """var x = { baz }
        |return x
        |""".stripMargin)

    assertEquals(
      parseStmts(
        """f()
          |g()
          |""".stripMargin),
      parseStmts(
        """f();
          |return g()
          |""".stripMargin))
  }

  test("Simple patterns") {
    parseMatchPattern("x")
    parseMatchPattern("Cons(x, y)")
    assertEquals(
      parseMatchPattern("_"),
      IgnorePattern())
    parseMatchPattern("Cons(x, Cons(x, Nil()))")
  }

  test("Block arguments") {
    parseExpr("map {f}")
    parseExpr("map {list::f}")
    parseExpr("map {f} {g}")
    parseExpr("map { f } { g }")
    parseExpr("map(x) { f } { g }")
    parseExpr("map(x) { return 42 }")
    parseExpr("map(x) { map(x) { return 42 } }")
  }

  test("Value types") {
    assertEquals(
      parseValueType("Int"),
      ValueTypeRef(IdRef(Nil, "Int"), Nil))

    parseValueType("List[Int]")
    parseValueType("list::List[Int]")
    parseValueType("list::List[effekt::Int]")
  }

  test("Block types") {
    parseBlockType("Exc")
    parseBlockType("State[S]")
    parseBlockType("State[Int]")
    parseBlockType("() => Int")
    parseBlockType("(Int) => Int")
    parseBlockType("(Int, String) => Int")
    parseBlockType("(Int, String) => Int / Exc")
    parseBlockType("[T](Int, String) => Int / { Exc, State[T] }")
    parseBlockType("=> Int")
    parseBlockType("{Exc} => Int")
    parseBlockType("{Exc} {Amb} => Int")
    parseBlockType("({Exc} {Amb} => Int)")
    parseBlockType("{Exc} {amb : Amb} => Int")
    parseBlockType("{exc:Exc} => Int")
    parseBlockType("[T] => T") // Not sure we want this...

    parseValueType("Exc at { a, b, c }")
    parseValueType("() => (Exc at {}) / {} at { a, b, c }")

    assertEquals(
      parseValueType("() => Int at { a, b, c }"),
      parseValueType("(() => Int) at { a, b, c }"))

    // we purposefully cannot parse this:
    intercept[Throwable] { parseValueType("() => Int at { a, b, c } at {}") }

    parseValueType("() => (Int at { a, b, c }) at {}")
    parseValueType("(() => Int) at { a, b, c }")
    parseValueType("(() => Int at {}) => Int at { a, b, c }")
  }

  test("Params") {
    parseParams("()")
    intercept[Throwable] { parseParams("(x, y)") }
    parseParams("[A, B](x: A, y: B)")
    intercept[Throwable] { parseParams("[]") }
    // is this desirable?
    parseParams("[A]")
  }

  test("Match clauses") {
    parseMatchClause("case x => 42")
    parseMatchClause("case Foo(x, y) => 42")
    parseMatchClause("case Foo(x, y) and x == 43 => 42")
    parseMatchClause("case Foo(Bar(42, true), _) and x == 43 => 42")
    parseMatchClause("case _ => 42")
    parseMatchClause("case a and a is Foo(bar) => 42")
  }

  test("Op clauses") {
    parseOpClause("def foo() = 42")
    parseOpClause("def foo[T]() = 42")
    parseOpClause("def foo[T](a) = 42")
    parseOpClause("def foo[T](a: Int) = 42")
    parseOpClause("def foo[T](a: Int, b) = 42")
    // TODO rebase!
    // parseOpClause("def foo[T](){f} = 42")
  }

  test("Implementations") {
    assertEquals(
      parseImplementation("Foo {}"),
      Implementation(BlockTypeRef(IdRef(Nil, "Foo"), Nil), Nil))

    parseImplementation("Foo[T] {}")
    parseImplementation("Foo[T] { def bar() = 42 }")
    parseImplementation(
      """Foo[T] {
        |  def bar() = 42
        |  def baz() = 42
        |}""".stripMargin)

    parseImplementation("Foo[T] { case Get(x) => 43 }")

    // Doesn't work yet
    parseImplementation("Foo { x => 43 }".stripMargin)

    assertEquals(
      parseImplementation("Foo { 43 }"),
      Implementation(
        BlockTypeRef(IdRef(Nil, "Foo"), Nil),
        List(OpClause(IdRef(Nil, "Foo"), Nil, Nil, Nil, None,
          Return(Literal(43, symbols.builtins.TInt)), IdDef("resume")))))
  }

  test("Try expressions") {
    parseTry("try 42 with Eff { 42 }")
    parseTry("try { 42 } with Eff { 42 }")
    parseTry("try { 42 } with Empty {}")
    parseTry("try { 42 } with Eff { def op(x) = x + 42 }")
    parseTry(
      """try {
      |  val x = 42
      |  do op(x + 1)
      |} with Eff[A] {
      |  def op(x: A) = x
      |}
      """.stripMargin
    )
    parseTry(
      """try { do op(42) }
      with Eff1 {}
      with Eff2 { case Get(x) => x + 1 }
      with Eff3 { def op(x, y) = { x } def op2() = { () }}
      """
    )
  }

  test("Type definition") {
    parseDefinition("type A = Int")
    parseDefinition("type A[X] = Int")
    parseDefinition("type A[X] { Foo() }")
    parseDefinition(
      """type A[X] {
        |  Foo();
        |  Bar()
        |}
        |""".stripMargin)
  }

  test("Namespaces") {
    parseDefinitions(
      """val x = 4
        |val y = 5
        |""".stripMargin)

    val nested = parseDefinitions(
      """namespace list {
        |  val x = 4
        |  val y = 5
        |}
        |""".stripMargin)

    val semi = parseDefinitions(
      """namespace list;
        |val x = 4
        |val y = 5
        |""".stripMargin)

    assertEquals(nested, semi)

    val nested2 = parseDefinitions(
      """namespace list {
        |  namespace internal {
        |
        |    val x = 4
        |    val y = 5
        |  }
        |}
        |""".stripMargin)

    val semi2 = parseDefinitions(
      """namespace list;
        |namespace internal;
        |
        |val x = 4
        |val y = 5
        |""".stripMargin)

    val semiInsertion = parseDefinitions(
      """namespace list
        |namespace internal
        |
        |val x = 4
        |val y = 5
        |""".stripMargin)

    assertEquals(nested2, semi2)
    assertEquals(nested2, semiInsertion)

    parseDefinitions(
      """val x = {
        |  namespace foo;
        |  val y = 4;
        |  foo::y
        |}
        |""".stripMargin)
  }

  test("Function definitions") {
    assertEquals(
      parseDefinition(
        """def foo = f
          |""".stripMargin),
      DefDef(IdDef("foo"), None, Var(IdRef(Nil, "f"))))

    parseDefinition(
        """def foo: Exc = f
          |""".stripMargin)

    parseDefinition(
        """def foo() = e
          |""".stripMargin)

    parseDefinition(
        """def foo[T] = e
          |""".stripMargin)

    parseDefinition(
        """def foo[T](x: Int) = e
          |""".stripMargin)

    parseDefinition(
        """def foo[T](x: Int): String / {} = e
          |""".stripMargin)
  }
}
