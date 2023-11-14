// TODO: 导入项目时，应选择 bsp 项目（而不是 sbt 项目，否则无法`自动`链接各种源码）。
//  导入成功后，在 Terminal 运行：
//  `sbt --java-home /Library/Java/JavaVirtualMachines/jdk-17-aarch64.jdk/Contents/Home`

name := baseDirectory.value.getName

organization := "hobby.wei.c"

version := "3.1.1"

scalaVersion := "2.12.18"

crossScalaVersions := Seq(
  "2.11.12",
  "2.12.18",
  "2.13.10"
)

//javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
// 启用对 java8 lambda 语法的支持。
//scalacOptions += "-Xexperimental"

// 解决生成文档报错导致 jitpack.io 出错的问题。
packageDoc / publishArtifact := false

// 独立使用本库的话，应该启用下面的设置。
//lazy val scalaSettings = Seq(
//  scalaVersion := "2.12.xx"
//)
//
//lazy val root = Project(id = "reflow", base = file("."))
//  .dependsOn(/*lang*/)
//  .settings(scalaSettings,
//    update / aggregate := false
//  )

exportJars := true

offline := true

// 如果要用 jitpack 打包的话就加上，打完了再注掉。
resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  // 如果要用 jitpack 打包的话就加上，打完了再注掉。
  // 如果独立使用本库，应该启用本依赖。
  "com.github.bdo-cash" % "annoguard" % "v2.0.0",
  // 如果用 jitpack 打包 2.12.12, 这个包的引入也必须是 2.12.12。
  "com.github.bdo-cash" % "scala-lang" % "60a29ca4c7", // scala 2.12

  // ScalaTest 的标准引用
  "junit"          % "junit"     % "[4.12,)"        % Test,
  "org.scalatest" %% "scalatest" % "[3.2.0-SNAP7,)" % Test
)

// 如果项目要独立编译，请同时启用这部分。
// Macro Settings
///*
resolvers ++= Resolver.sonatypeOssRepos("releases")
libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, x)) if x < 13 =>
    addCompilerPlugin("org.scalamacros" % "paradise"       % "[2.1.0,)" cross CrossVersion.full)
    Seq("org.scala-lang"                % "scala-compiler" % scalaVersion.value)
  case _ => Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value)
})
Compile / scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, x)) if x >= 13 => Seq("-Ymacro-annotations")
  case _                       => Nil
})
//*/
