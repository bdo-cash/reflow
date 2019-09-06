name := baseDirectory.value.getName

organization := "hobby.wei.c"

version := "1.0.6"

scalaVersion := "2.11.11"

crossScalaVersions := Seq(
  /*"2.11.7", 多余，不需要两个*/
  "2.11.11",
  /*"2.12.2", 有一些编译问题：`the interface is not a direct parent`。*/
  "2.12.6")

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
// 启用对 java8 lambda 语法的支持。
scalacOptions += "-Xexperimental"

// 解决生成文档报错导致 jitpack.io 出错的问题。
publishArtifact in packageDoc := false

// TODO: 独立使用本库的话，应该启用下面的设置。
//lazy val scalaSettings = Seq(
//  scalaVersion := "2.11.11"
//)
//
//lazy val root = Project(id = "reflow", base = file("."))
//  .dependsOn(/*lang*/)
//  .settings(scalaSettings,
//    aggregate in update := false
//  )

exportJars := true

offline := true

// 如果要用 jitpack 打包的话就加上，打完了再注掉。
resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  // 如果要用 jitpack 打包的话就加上，打完了再注掉。
  // TODO: 独立使用本库的话，应该启用本依赖。
  "com.github.dedge-space" % "annoguard" % "1.0.3-beta",
  // TODO: 如果 jitpack 打包 2.12.6, 这个包的引入也必须是 2.12.6，切记切记。
  "com.github.dedge-space" % "scala-lang" % "ccaa2cb00f",

  // ScalaTest 的标准引用。
  "junit" % "junit" % "[4.12,)" % Test,
  // `3.2.0-SNAP10`会导致`scala.ScalaReflectionException: object org.scalatest.prop.Configuration$ not found`.
  "org.scalatest" %% "scalatest" % "3.2.0-SNAP7" % Test
)
