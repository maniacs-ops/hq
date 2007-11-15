import org.hyperic.hq.measurement.server.session.DataPoint
import org.hyperic.hq.product.MetricValue
import org.hyperic.hq.measurement.server.session.DataManagerEJBImpl as dM

/**
 * This script loads the data manager with dummy metric data points, 
 * optionally inserting dummy backfiller metric data points.
 */
 
// SCRIPT VARIABLES ASSIGNED HERE

def totalNumInserters = 10              // the total number of data inserter threads

def numDataPointsPerInserter = 200      // number of data points inserted per thread

def pauseTimeBetweenInserts = 1000      // per thread pause time between each data insert (msec)

def pauseBetweenInserterStarts = 2000   // pause time between starting each data inserter thread (msec)

def numBackfillers = 5                  // the total number of data inserter threads doing backfilling
										                    // any value > totalNumInserters is ignored

def totalRunTime = 60000                // total test run time (msec)


// SCRIPT EXECUTION STARTS HERE

runTest(totalNumInserters, 
        numDataPointsPerInserter, 
        pauseTimeBetweenInserts, 
        pauseBetweenInserterStarts, 
        numBackfillers, 
        totalRunTime)



// HELPER FUNCTIONS

def runTest(totalNumInserters, 
            numDataPointsPerInserter, 
            pauseTimeBetweenInserts, 
            pauseBetweenInserterStarts, 
            numBackfillers, 
            totalRunTime) {

  // make all the measurement events interesting so we flood the event bus
  System.setProperty(dM.ALL_EVENTS_INTERESTING_PROP, "true")

  try {
    def startTime = System.currentTimeMillis()

    def inserterThreads = startDataInserters(totalNumInserters, 
										                         numDataPointsPerInserter, 
										                         pauseTimeBetweenInserts, 
										                         pauseBetweenInserterStarts,
										                         numBackfillers)

    def sleepTime = totalRunTime-(System.currentTimeMillis()-startTime)

    if (sleepTime > 0) Thread.sleep(sleepTime)

    inserterThreads.each {
      it.interrupt()  
    }

    inserterThreads.each {
      while (it.isAlive())  {
        it.interrupt()
        it.join(10000)
      }
    }

    inserterThreads.each {
      println(it.toString()+" thread is still active ="+it.isAlive())
    }

  } finally {
    System.setProperty(dM.ALL_EVENTS_INTERESTING_PROP, "false")
  }
}

def startDataInserters(totalNumInserters, 
						           numDataPointsPerInserter, 
						           pauseTimeBetweenInserts, 
						           pauseBetweenInserterStarts,
						           numBackfillers) {

  def inserterThreads = new ArrayList(totalNumInserters)

  def currentDataPointId = 100

  for (i in 1..totalNumInserters) {
    def startDataPointId = currentDataPointId
    def endDataPointId = startDataPointId+numDataPointsPerInserter-1
    def runBackfiller = i <= numBackfillers
    def dataInserter = startDataInserterThread(startDataPointId, 
    											                     endDataPointId, 
    											                     pauseTimeBetweenInserts, 
    											                     runBackfiller)
    inserterThreads.add(dataInserter)
    Thread.sleep(pauseBetweenInserterStarts)
    currentDataPointId = endDataPointId+1
  }
  
  return inserterThreads
}

def startDataInserterThread(startDataPointId, 
                            endDataPointId, 
                            insertPauseTime, 
                            runBackfiller) {
  def name = "data inserter "+startDataPointId+":"+endDataPointId
  
  def runnable = [run: {
    def random = new Random()

    while (true) {
      try {
        Thread.sleep(insertPauseTime)
      } catch (InterruptedException e) {
        return
      }
      
      def dataPoints = getDataPoints(startDataPointId, endDataPointId, random)

      // println(name+" "+dataPoints)
      
      try {
        // randomly insert backfiller metrics before the true data points
        if (runBackfiller && random.nextBoolean()) {
          def backfilled = dataPoints.subList(0, random.nextInt(dataPoints.size()))
          dM.one.addData(backfilled, false)        
        }
      
        dM.one.addData(dataPoints)      
      } catch (InterruptedException e) {
        // data insertion may be interrupted when the test is complete
        return
      }
    }
  }] as Runnable

  def thread = new Thread(runnable, name)
  thread.setDaemon(true)
  thread.start()

  return thread
}

def getDataPoints(startDataPointId, endDataPointId, random) {
  def dataPoints = new ArrayList()
  def currentTime = System.currentTimeMillis()

  for (i in startDataPointId..endDataPointId) {
    def mvalue = Math.abs(random.nextLong())
    // the absolute value of Long.MIN_VALUE is Long.MIN_VALUE
    if (mvalue < 0) mvalue = Long.MAX_VALUE    
  
    def mv = new MetricValue(mvalue, currentTime)
    def dp = new DataPoint(i, mv)
    dataPoints.add(dp)          
  }

  return dataPoints
}					   