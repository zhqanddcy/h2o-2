#----------------------------------------------------------------------
# Try to slice by using != factor_level
#----------------------------------------------------------------------

if (TRUE) {
  # Set working directory so that the source() below works.
  setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))

  if (FALSE) {
      setwd("/Users/tomk/0xdata/ws/h2o/R/tests/testdir_demos")
  }

  source('../../findNSourceUtils.R')
  options(echo=TRUE)
  filePath <- normalizePath(locate("smalldata/airlines/allyears2k_headers.zip"))
  testFilePath <- normalizePath(locate("smalldata/airlines/allyears2k_headers.zip"))
} else {
  stop("need to hardcode ip and port")
  # myIP = "127.0.0.1"
  # myPort = 54321

  library(h2o)
  PASS_BANNER <- function() { cat("\nPASS\n\n") }
  filePath <- "https://raw.github.com/0xdata/h2o/master/smalldata/airlines/allyears2k_headers.zip"
  testFilePath <-"https://raw.github.com/0xdata/h2o/master/smalldata/airlines/allyears2k_headers.zip"
}

conn <- h2o.init(ip=myIP, port=myPort, startH2O=FALSE)

# Uploading data file to h2o.
air = h2o.importFile(conn, filePath, "air")

# Print dataset size.
dim(air)

#
# Example 1: Select all flights not departing from SFO
#

not.sfo <- air[air$Origin != "SFO",]
print(dim(not.sfo))

PASS_BANNER()
