name := baseDirectory.value.getName

organization := "hobby.wei.c"

version := "3.0.6"

scalaVersion := "2.12.17"

crossScalaVersions := Seq(
  "2.11.12",
  "2.12.17",
  "2.13.10"
)

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
// 启用对 java8 lambda 语法的支持。
scalacOptions += "-Xexperimental"

// 解决生成文档报错导致 jitpack.io 出错的问题。
publishArtifact in packageDoc := false

// 独立使用本库的话，应该启用下面的设置。
//lazy val scalaSettings = Seq(
//  scalaVersion := "2.11.12"
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
  // 如果独立使用本库，应该启用本依赖。
  "com.github.bdo-cash" % "annoguard" % "v1.0.6",
  // 如果用 jitpack 打包 2.12.12, 这个包的引入也必须是 2.12.12。
  //"com.github.bdo-cash" % "scala-lang" % "138bff0c11",
  "com.github.bdo-cash" % "scala-lang" % "ccf5b69c86", // scala 2.12

  // ScalaTest 的标准引用
  "junit"          % "junit"     % "[4.12,)"        % Test,
  "org.scalatest" %% "scalatest" % "[3.2.0-SNAP7,)" % Test
)

// 如果项目要独立编译，请同时启用这部分。
// Macro Settings
///*
resolvers += Resolver.sonatypeRepo("releases")
libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, x)) if x < 13 =>
    addCompilerPlugin("org.scalamacros" % "paradise"       % "[2.1.0,)" cross CrossVersion.full)
    Seq("org.scala-lang"                % "scala-compiler" % scalaVersion.value)
  case _ => Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value)
})
scalacOptions in Compile ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, x)) if x >= 13 => Seq("-Ymacro-annotations")
  case _                       => Nil
})
//*/
