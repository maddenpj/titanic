import scala.collection.JavaConverters._
import org.apache.spark.sql.SparkSession
import org.apache.spark.mllib.classification.{SVMModel,SVMWithSGD}
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.linalg.{Vector,Vectors}
import org.apache.spark.sql.{Row, Encoders, Encoder}
import org.apache.spark.ml.param.ParamMap
import org.apache.log4j.Logger
import org.apache.log4j.Level


case class VectorRecord( label: Int, features: org.apache.spark.ml.linalg.Vector)

object Titanic {

  def main(args: Array[String]) {

    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    implicit val spark = SparkSession.builder.appName("Simple Application").getOrCreate()
    spark.sparkContext.setLogLevel("OFF")
    val input = spark
      .read
      .format("csv")
      .option("sep",",")
      .option("inferSchema","true")
      .option("header","true")
      .load("src/main/resources/train.csv")
    input.printSchema()

    println(input.getClass)
    basicAnalysis(input, 0)

    val inputlist = input.takeAsList( input.count().toInt ).asScala.toList
    val transformed = inputMassage(inputlist, 0)

    //NOTE: If we wanted to convert to RDD from List, this is how
    val inputrdd = spark.sparkContext.parallelize(transformed,1)
    //inputrdd.take(4).foreach(println)

    //create dataframe from massaged data
    val inputdf = spark.createDataFrame(transformed).toDF("label", "features")

    //create logistic regression model with simple parameters 
    var lr = new LogisticRegression()
    lr.setMaxIter(10).setRegParam(0.01)

    //fit new model using above params
    var model1 = lr.fit(inputdf)

    //load test data
    val testinput = spark
      .read
      .format("csv")
      .option("sep",",")
      .option("inferSchema","true")
      .option("header","true")
      .load("src/main/resources/test.csv")
    testinput.printSchema()

    //testinput has no survived column, so pass '1' as param to indicate not to mess with this
    basicAnalysis(testinput, 1)

    //massage test data like train
    val testinputlist = testinput.takeAsList( testinput.count().toInt ).asScala.toList
    val testtransformed = inputMassage(testinputlist, 1)

    //create test dataframe
    val testinputdf = spark.createDataFrame(testtransformed).toDF("label", "features")

    //actual fitting to earlier model
    val results = model1.transform(testinputdf)

    //There is a 'probability' column that exists but we don't care for this attempt
    /*results.select("features","probability","prediction").collect()
    .foreach({ case Row(features: Vector, prob: Vector, prediction:Double) =>
       println(s"($features) -> prob=$prob, prediction=$prediction")
    })*/

    //collect results
    val testpids = results.select("features","prediction").collect()

    //map into output format, casting as necessary
    val testresults = testpids.map{ x => 
      (x.apply(0).asInstanceOf[Vector].apply(0).floor.toInt, x.apply(1).asInstanceOf[Double].floor.toInt)
    } 

    //create output dataframe and write
    val testresultsdf = spark.createDataFrame(testresults).toDF("label", "features")
    testresultsdf.coalesce(1).write.format("csv").save("./results.csv")

  }
  def inputMassage(input: List[org.apache.spark.sql.Row], testdata: Int) = {
      //if testdata == 1, then we ignore survived column
      input.map { row =>
        val label = if( testdata == 0) row.getInt(row.fieldIndex("Survived")) else 0
        val featureCols = row.schema.fieldNames.filter( x => ( (x != "Survived") && (x != "Name") && (x != "Age") && (x != "Cabin") && (x != "Ticket") && (x != "Embarked") ) ) 
        // need to properly handle embarked, age, and ticket at some point instead of throwing it away, TODO
        (label, row.getValuesMap(featureCols) )
      }.map{ row => 
        
          val sex = if (row._2("Sex").toString == "male") 1 else 2
          (row._1, Vectors.dense( row._2("PassengerId"):Int, row._2("Pclass"):Int, row._2("SibSp"):Int, row._2("Parch"):Int,row._2("Fare") , sex ) ) 
      }

  }
  def basicAnalysis(input: org.apache.spark.sql.DataFrame, testdata: Int) {
    //if testdata == 1, then we ignore survived column
    if (testdata == 0)
    	input.filter( input("Survived") === "1").show()

    val totalrecords = input.count()

    def nullPercent(title: String, x: Long) =
      s"$title: Found $x null entries, $totalrecords total entries, ${ 100*x.toFloat/totalrecords }% null"

    var columns = input.columns

    val nulls = input.columns.map( column => ( column, input.filter( row => row( columns.indexOf(column) ) == null ).count() ) ) 

    val nullstrings = nulls.map( x  => nullPercent(x._1, x._2) )
    nullstrings.foreach( println )
  }

}
