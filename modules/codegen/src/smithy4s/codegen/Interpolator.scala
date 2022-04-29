package smithy4s.codegen

import cats.implicits._
object Interpolator {
  class Interpolator(val sc: StringContext) extends AnyVal {
    def render(renderables: Renderable.WithValue[_]*): RenderLine = {
      renderAndCombine(renderables.toList)
    }

    private def renderAndCombine(
        renderables: List[Renderable.WithValue[_]]
    ): RenderLine = {
      def aux[A](binding: Renderable.WithValue[A]): RenderLine = {
        val (imports,lines) =binding.render.tupled
        RenderLine(imports,lines.mkString(""))
      }
      val renderLines: List[RenderLine] = renderables.map(r => aux(r))
      sc.parts.toList
        .map(RenderLine(_))
        .zipAll(renderLines, RenderLine.empty, RenderLine.empty)
        .flatMap { case (a, b) => List(a, b) }
        .combineAll
        }

    }
}

trait RenderableInterpolator {
  implicit def renderResultInterpolator(
      sc: StringContext
  ): Interpolator.Interpolator = new Interpolator.Interpolator(sc)
}

object test extends RenderableInterpolator with App {
  import Renderable.WithValue._
  implicit val intRenderable: Renderable[Int] = (i) =>
    RenderResult(Set.empty, List(s"$i"))

  val string = "hello"
  val int = 1
  // println(render"test $string more tests $int test")
  println(render"$string more tests $int")
}
