package io.github.qf6101.mfm.factorization.binomial

import better.files.File
import breeze.linalg.{DenseMatrix, DenseVector}
import io.github.qf6101.mfm.baseframe.Coefficients
import io.github.qf6101.mfm.util.GaussianRandom
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.tools.read.{SimpleReadSupport, SimpleRecord}
import org.apache.spark.sql.SparkSession
import org.json4s.DefaultFormats
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import scala.collection.mutable.ListBuffer
import scala.math._

/**
  * Created by qfeng on 15-3-12.
  */

/**
  * Factorization Machine模型系数
  *
  * @param initMean    随机初始值均值
  * @param initStdev   随机初始值标准差
  * @param numFeatures 特征个数
  * @param numFactors  因子个数
  * @param k0          是否需要处理截距
  * @param k1          是否需要处理一阶参数
  * @param k2          是否需要处理二阶参数
  */
class FmCoefficients(val initMean: Double,
                     val initStdev: Double,
                     var numFeatures: Int,
                     var numInteractFeatures: Int,
                     var numFactors: Int,
                     val k0: Boolean,
                     val k1: Boolean,
                     val k2: Boolean) extends Coefficients {
  var w0 = GaussianRandom.rand(initMean, initStdev)
  var w = DenseVector.zeros[Double](numFeatures)
  var v = GaussianRandom.randDenseMatrix(initMean, initStdev, numInteractFeatures, numFactors)

  /**
    * 用breeze稀疏向量和CSC稀疏矩阵初始化模型系数
    *
    * @param w0 0阶系数
    * @param w  1阶系数
    * @param v  2阶系数
    * @param k0 是否需要处理截距
    * @param k1 是否需要处理一阶参数
    * @param k2 是否需要处理二阶参数
    */
  def this(w0: Double, w: DenseVector[Double], v: DenseMatrix[Double], k0: Boolean, k1: Boolean, k2: Boolean) {
    this(0.0, 0.0, w.length, v.rows, v.cols, k0, k1, k2)
    this.w0 = w0
    this.w = w.copy
    this.v = v.copy
  }

  /**
    * 只复制this的结构（比如参数个数），不复制内容
    *
    * @return 复制的拷贝
    */
  override def copyEmpty(): Coefficients = new FmCoefficients(this.initMean, this.initMean,
    this.numFeatures, this.numInteractFeatures, this.numFactors, this.k0, this.k1, this.k2)

  /**
    * 对应系数加法，加至this上
    *
    * @param other 加数
    * @return this
    */
  override def +=(other: Coefficients): Coefficients = {
    val otherCoeffs = other.asInstanceOf[FmCoefficients]
    if (k0) this.w0 += otherCoeffs.w0
    if (k1) this.w += otherCoeffs.w
    if (k2) this.v += otherCoeffs.v
    this
  }

  /**
    * 对应系数减法，减至this上
    *
    * @param other 减数
    * @return this
    */
  override def -=(other: Coefficients): Coefficients = {
    val otherCoeffs = other.asInstanceOf[FmCoefficients]
    if (k0) this.w0 -= otherCoeffs.w0
    if (k1) this.w -= otherCoeffs.w
    if (k2) this.v -= otherCoeffs.v
    this
  }

  /**
    * 对应系数加上同一实数，加至复制this的类上
    *
    * @param addend 加数
    * @return 加法结果（拷贝）
    */
  override def +(addend: Double): Coefficients = {
    val result = this.copy.asInstanceOf[FmCoefficients]
    if (k0) result.w0 += addend
    if (k1) result.w += addend
    if (k2) result.v += addend
    result
  }

  /**
    * 对应系数乘上同一实数，加至复制this的类上
    *
    * @param multiplier 乘数
    * @return 乘法结果
    */
  override def *(multiplier: Double): Coefficients = {
    val result = this.copy.asInstanceOf[FmCoefficients]
    if (k0) result.w0 *= multiplier
    if (k1) result.w *= multiplier
    if (k2) result.v *= multiplier
    result
  }

  /**
    * 同时复制this的结构和内容
    *
    * @return 复制的拷贝
    */
  override def copy: Coefficients = {
    //从效率出发，参数设为0
    val coeffs = new FmCoefficients(this.initMean, this.initStdev, 0, 0, 0, this.k0, this.k1, this.k2)
    coeffs.numFeatures = this.numFeatures
    coeffs.numInteractFeatures = this.numInteractFeatures
    coeffs.numFactors = this.numFactors
    coeffs.w0 = this.w0
    coeffs.w = this.w.copy
    coeffs.v = this.v.copy
    coeffs
  }

  /**
    * 对应系数除上同一实数，加至复制this的类上
    *
    * @param dividend 除数
    * @return 除法结果
    */
  override def /(dividend: Double): Coefficients = {
    val result = this.copy.asInstanceOf[FmCoefficients]
    if (k0) result.w0 /= dividend
    if (k1) result.w /= dividend
    if (k2) result.v /= dividend
    result
  }

  /**
    * 计算L2的正则值
    *
    * @param reg 正则参数
    * @return 参数加权后的L2正则值
    */
  override def L2RegValue(reg: Array[Double]): Double = {
    val zeroRegValue = if (k0) w0 * w0 * reg(0) else 0.0
    val firstRegValue = if (k1 && w.activeSize > 0) w.activeValuesIterator.reduce(_ + Math.pow(_, 2)) * reg(1) else 0.0
    val secondRegValue = if (k2 && v.activeSize > 0) v.activeValuesIterator.reduce(_ + Math.pow(_, 2)) * reg(2) else 0.0
    0.5 * (zeroRegValue + firstRegValue + secondRegValue)
  }

  /**
    * 计算L2的正则梯度值
    *
    * @param reg 正则参数
    * @return 参数加权后的L2正则梯度值
    */
  override def L2RegGradient(reg: Array[Double]): Coefficients = {
    val result = this.copy.asInstanceOf[FmCoefficients]
    if (k0) result.w0 *= reg(0)
    if (k1) result.w *= reg(1)
    if (k2) result.v *= reg(2)
    result
  }

  /**
    * 用L1稀疏化系数
    *
    * @param regParam 正则参数值
    * @param stepSize 学习率
    * @return 稀疏化后的系数
    */
  override def L1Shrink(regParam: Array[Double], stepSize: Double): Coefficients = {
    //0阶参数
    if (k0) {
      val zeroShrinkageVal = regParam(0) * stepSize
      w0 = signum(w0) * max(0.0, abs(w0) - zeroShrinkageVal)
    }
    //1阶参数
    if (k1) {
      val firstShrinkageVal = regParam(1) * stepSize
      val newW = DenseVector.zeros[Double](w.length)
      w.activeIterator.foreach { case (index, weight) =>
        val newWeight = signum(weight) * max(0.0, abs(weight) - firstShrinkageVal)
        if (newWeight == 0) {
          Nil
        } else {
          newW.update(index, newWeight)
        }
      }
      w = newW
    }
    //2阶参数
    if (k2) {
      val secondShrinkageVal = regParam(2) * stepSize / numFactors
      val newV = DenseMatrix.zeros[Double](v.rows, v.cols)
      v.activeIterator.foreach { case ((rowIndex, colIndex), weight) =>
        val newWeight = signum(weight) * max(0.0, abs(weight) - secondShrinkageVal)
        if (newWeight == 0) {
          Nil
        } else {
          newV.update(rowIndex, colIndex, newWeight)
        }
      }
      v = newV
    }
    //全部更新完后，返回结果
    this
  }

  /**
    * 计算L1的正则值
    *
    * @param reg 正则参数
    * @return 参数绝对值加权后的L1正则值
    */
  override def L1RegValue(reg: Array[Double]): Double = {
    val zeroRegValue = if (k0) abs(w0) * reg(0) else 0.0
    val firstRegValue = if (k1 && w.activeSize > 0) w.activeIterator.foldLeft(0.0) { case (absSum, (_, weight)) =>
      absSum + abs(weight)
    } * reg(1)
    else 0.0
    val secondRegValue = if (k2 && v.activeSize > 0) v.activeIterator.foldLeft(0.0) { case (absSum, (_, weight)) =>
      absSum + abs(weight)
    } * reg(2)
    else 0.0
    zeroRegValue + firstRegValue + secondRegValue
  }

  /**
    * 计算系数的2范数
    * sum(abs(A).^p)^(1/p) where p=2
    *
    * @return 系数的2范数
    */
  override def norm: Double = {
    val zeroSum = if (k0) w0 * w0 else 0.0
    val firstSum = if (k1 && w.activeSize > 0) w.activeIterator.foldLeft(0.0) { case (sum: Double, (_, value: Double)) =>
      sum + value * value
    } else 0.0
    val secondSum = if (k2 && v.activeSize > 0) v.activeIterator.foldLeft(0.0) { case (sum: Double, (_, value: Double)) =>
      sum + value * value
    } else 0.0
    math.sqrt(zeroSum + firstSum + secondSum)
  }

  /**
    * 保存元数据至文件
    *
    * @param location 文件位置
    */
  override def saveMeta(location: String): Unit = {
    val json = (Coefficients.namingCoeffType -> FmCoefficients.getClass.toString) ~
      (FmCoefficients.namingIntercept -> w0) ~
      (FmCoefficients.namingWSize -> w.size) ~
      (FmCoefficients.namingVRows -> v.rows) ~
      (FmCoefficients.namingVCols -> v.cols) ~
      (FmCoefficients.namingK0 -> k0) ~
      (FmCoefficients.namingK1 -> k1) ~
      (FmCoefficients.namingK2 -> k2)
    SparkSession.builder().getOrCreate().sparkContext.
      makeRDD(List(compact(render(json)))).repartition(1).saveAsTextFile(location)
  }

  /**
    * 保存数据至文件
    *
    * @param location 文件位置
    */
  override def saveData(location: String): Unit = {
    val spark = SparkSession.builder().getOrCreate()
    spark.createDataFrame(w.data.map(Tuple1(_))).repartition(1).toDF("value").write.parquet(location + "/w")
    spark.createDataFrame(v.data.map(Tuple1(_))).repartition(1).toDF("value").write.parquet(location + "/v")
  }

  /**
    * 与另一个系数是否相等
    *
    * @param other 另一个系数
    * @return 是否相等
    */
  override def equals(other: Coefficients): Boolean = {
    other match {
      case otherCoeffs: FmCoefficients =>
        if (w0 == otherCoeffs.w0 && w.equals(otherCoeffs.w) && v.equals(otherCoeffs.v)) true else false
      case _ => false
    }
  }
}

/**
  * 静态FM系数对象
  */
object FmCoefficients {
  val namingIntercept = "intercept"
  val namingWSize = "w_size"
  val namingVRows = "v_rows"
  val namingVCols = "v_cols"
  val namingK0 = "k0"
  val namingK1 = "k1"
  val namingK2 = "k2"

  /**
    * 系数文件构造分解机系数
    *
    * @param location 系数文件
    * @return 分解机系数
    */
  def apply(location: String): FmCoefficients = {
    //初始化spark session
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    //读取元数据
    val meta = spark.read.json(location + "/" + Coefficients.namingMetaFile).first()
    val w0 = meta.getAs[Double](namingIntercept)
    val vRows = meta.getAs[Long](namingVRows).toInt
    val vCols = meta.getAs[Long](namingVCols).toInt
    val k0 = meta.getAs[Boolean](namingK0)
    val k1 = meta.getAs[Boolean](namingK1)
    val k2 = meta.getAs[Boolean](namingK2)
    // 读取w向量
    val w = DenseVector(spark.read.parquet(location + "/" + Coefficients.namingDataFile + "/w").map { row =>
      row.getAs[Double]("value")
    }.collect())
    // 读取v向量
    val v = DenseMatrix.create(vRows, vCols, spark.read.parquet(location + "/" + Coefficients.namingDataFile + "/v")
      .map { row =>
        row.getAs[Double]("value")
      }.collect())
    //返回结果
    new FmCoefficients(w0, w, v, k0, k1, k2)
  }

  /**
    * 从本地文件载入系数
    *
    * @param location 本地文件
    * @return FM系数对象
    */
  def fromLocal(location: String): FmCoefficients = {
    //读取元数据
    implicit val formats = DefaultFormats
    val meta = parse(File(location + "/" + Coefficients.namingMetaFile + "/part-00000").contentAsString)
    val w0 = (meta \ namingIntercept).extract[Double]
    val vRows = (meta \ namingVRows).extract[Int]
    val vCols = (meta \ namingVCols).extract[Int]
    val k0 = (meta \ namingK0).extract[Boolean]
    val k1 = (meta \ namingK1).extract[Boolean]
    val k2 = (meta \ namingK2).extract[Boolean]
    // 读取w和v向量
    val w = DenseVector(readValues(location + "/" + Coefficients.namingDataFile + "/w"))
    val v = DenseMatrix.create(vRows, vCols, readValues(location + "/" + Coefficients.namingDataFile + "/v"))
    //返回结果
    new FmCoefficients(w0, w, v, k0, k1, k2)
  }

  /**
    * 从本地文件读取系数内容
    *
    * @param location 本地文件
    * @return 系数对象
    */
  def readValues(location: String): Array[Double] = {
    val reader = ParquetReader.builder[SimpleRecord](new SimpleReadSupport(), new Path(location)).build()
    val values = ListBuffer[Double]()
    var elem = reader.read()
    while (elem != null) {
      values += elem.getValues.get(0).getValue.asInstanceOf[Double]
      elem = reader.read()
    }
    reader.close()
    values.toArray
  }
}