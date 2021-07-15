################### data upload ###################
# First upload the data into the minio            #
# In the folder test/data you can find 4 testsets #
# Navigate to http://localhost:9000               #
# Login with molgenis:molgenis                    #
# Create a bucket called shared-local             #
# Create a folder called 1_0_core_1_0             #
# Upload the data into this folder                #
# Create a folder called 1_0_outcome_1_0          #
# Upload the core outcome data into this folder   #
###################################################

################### data analysis #################
# Run a simple summary static on birth age        #
###################################################

install.packages(c("DSMolgenisArmadillo", "DSI"))
install.packages("remotes")
library(remotes)
install_github("datashield/dsBaseClient", ref="6.1.0")

library(DSMolgenisArmadillo)
library(dsBaseClient)

builder <- DSI::newDSLoginBuilder()
builder$append(
  server = "local",
  url = "http://localhost:8080",
  user = "admin",
  password = "admin",
  driver = "ArmadilloDriver",
  table = "local/1_0_core_1_0/nonrep")

login_data <- builder$build()

conns <- DSI::datashield.login(logins = login_data, assign = TRUE)
datashield.tables(conns = conns)
ds.ls(datasources = conns)
ds.colnames("D")
ds.summary("D$agebirth_m_d")

datashield.logout(conns)