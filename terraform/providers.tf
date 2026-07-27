provider "oci" {
  alias               = "primary"
  region              = var.primary_region
  auth                = var.oci_auth
  config_file_profile = var.oci_config_profile
}
provider "oci" {
  alias               = "standby"
  region              = var.standby_region
  auth                = var.oci_auth
  config_file_profile = var.oci_config_profile
}
