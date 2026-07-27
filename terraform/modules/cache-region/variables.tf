variable "compartment_id" {
  type = string
}

variable "name_prefix" {
  type = string
}

variable "role" {
  type = string
}

variable "create_network" {
  type = bool
}

variable "existing_vcn_id" {
  type     = string
  default  = null
  nullable = true
}

variable "existing_subnet_id" {
  type     = string
  default  = null
  nullable = true
}

variable "existing_nsg_ids" {
  type    = list(string)
  default = []
}

variable "vcn_cidr" {
  type = string
}

variable "cache_subnet_cidr" {
  type = string
}

variable "vcn_dns_label" {
  type = string
}

variable "subnet_dns_label" {
  type = string
}

variable "application_cidrs" {
  type = set(string)
}

variable "cluster_mode" {
  type = string
}

variable "software_version" {
  type = string
}

variable "node_count" {
  type = number
}

variable "node_memory_in_gbs" {
  type = number
}

variable "shard_count" {
  type = number
}

variable "create_cache_user" {
  type = bool
}

variable "cache_user_name" {
  type = string
}

variable "cache_user_description" {
  type = string
}

variable "cache_user_acl" {
  type = string
}

variable "cache_user_password_hashes" {
  type      = list(string)
  sensitive = true
}

variable "existing_cache_user_ocids" {
  type    = list(string)
  default = []
}

variable "freeform_tags" {
  type    = map(string)
  default = {}
}
