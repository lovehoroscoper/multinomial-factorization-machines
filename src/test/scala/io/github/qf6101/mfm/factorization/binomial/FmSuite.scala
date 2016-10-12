package io.github.qf6101.mfm.factorization.binomial

import io.github.qf6101.mfm.optimization.SquaredL2Updater
import io.github.qf6101.mfm.tuning.BinaryClassificationMetrics
import io.github.qf6101.mfm.util.TestingUtils._
import io.github.qf6101.mfm.util.{HDFSUtil, LoadDSUtil, MfmTestSparkSession}
import org.apache.spark.ml.param.ParamMap
import org.scalatest.FunSuite

/**
  * User: qfeng
  * Date: 15-12-8 下午4:58
  */
class FmSuite extends FunSuite with MfmTestSparkSession {
  test("test binomial factorization machines") {
    // Load training and testing data sets
    val (training, _) = LoadDSUtil.loadLibSVMDataSet("test_data/input/a1a/a1a")
    val (testing, numFeatures) = LoadDSUtil.loadLibSVMDataSet("test_data/input/a1a/a1a.t")
    // Construct factorization machines learner with parameters
    val params = new ParamMap()
    val updater = new SquaredL2Updater()
    val fmLearn = new FmLearnSGD(params, updater)
    params.put(fmLearn.gd.numIterations, 10)
    params.put(fmLearn.gd.stepSize, 1.0)
    params.put(fmLearn.gd.miniBatchFraction, 1.0)
    params.put(fmLearn.gd.convergenceTol, 1E-5)
    params.put(fmLearn.numFeatures, numFeatures)
    params.put(fmLearn.numFactors, 5)
    params.put(fmLearn.k0, true)
    params.put(fmLearn.k1, true)
    params.put(fmLearn.k2, true)
    params.put(fmLearn.maxInteractFeatures, numFeatures)
    params.put(fmLearn.initMean, 0.0)
    params.put(fmLearn.initStdev, 0.0001)
    params.put(fmLearn.reg0, 0.0001)
    params.put(fmLearn.reg1, 0.0001)
    params.put(fmLearn.reg2, 0.001)
    // Train FM model
    val model = fmLearn.train(training)
    // Use testing data set to evaluate the model
    val eval = testing.map { case (label, features) =>
      (model.predict(features), label)
    }
    val metrics = new BinaryClassificationMetrics(eval)
    // Save model to file
    HDFSUtil.deleteIfExists("test_data/output/a1a")
    model.save("test_data/output/a1a")

    //// Firstly test spark reloading
    // Reload model from file and test if it is equal to the original model
    val sparkReloadModel = FmModel("test_data/output/a1a")
    assert(model.equals(sparkReloadModel))
    // Evaluate the reloaded model
    val sparkReloadEval = testing.map { case (label, features) =>
      (sparkReloadModel.predict(features), label)
    }
    // Test if the reloaded model has the same result on the testing data set
    val sparkReloadMetrics = new BinaryClassificationMetrics(sparkReloadEval)
    assert(sparkReloadMetrics.AUC ~= metrics.AUC absTol 1E-5)

    //// Secondly test local reloading
    // Reload model from file and test if it is equal to the original model
    val localReloadModel = FmModel.fromLocal("test_data/output/a1a")
    assert(model.equals(localReloadModel))
    // Evaluate the reloaded model
    val localReloadEval = testing.map { case (label, features) =>
      (localReloadModel.predict(features), label)
    }
    // Test if the reloaded model has the same result on the testing data set
    val localReloadMetrics = new BinaryClassificationMetrics(localReloadEval)
    assert(localReloadMetrics.AUC ~= metrics.AUC absTol 1E-5)
    // print the AUC
    println("AUC: " + metrics.AUC)
  }
}
