locals {
  common_tags = merge(
    {
      project      = var.project_name
      "managed-by" = "terraform"
    },
    var.freeform_tags,
  )
}

module "primary" {
  source = "./modules/cache-region"

  providers = {
    oci = oci.primary
  }

  compartment_id = var.compartment_id
  name_prefix     = "${var.project_name}-primary"
  role            = "primary"

  create_network     = var.create_networks
  existing_vcn_id    = var.primary_existing_vcn_id
  existing_subnet_id = var.primary_existing_subnet_id
  existing_nsg_ids   = var.primary_existing_nsg_ids
  vcn_cidr           = var.primary_vcn_cidr
  cache_subnet_cidr  = var.primary_cache_subnet_cidr
  vcn_dns_label      = var.primary_vcn_dns_label
  subnet_dns_label   = var.primary_subnet_dns_label
  application_cidrs  = var.primary_application_cidrs

  cluster_mode       = var.cluster_mode
  software_version   = var.software_version
  node_count         = var.node_count
  node_memory_in_gbs = var.node_memory_in_gbs
  shard_count        = var.shard_count

  create_cache_user          = var.create_cache_user
  cache_user_name            = var.cache_user_name
  cache_user_description     = "Application user for ${var.project_name} in ${var.primary_region}"
  cache_user_acl             = var.cache_user_acl
  cache_user_password_hashes = var.cache_user_password_hashes
  existing_cache_user_ocids  = var.primary_existing_cache_user_ocids

  freeform_tags = local.common_tags
}

module "standby" {
  source = "./modules/cache-region"

  providers = {
    oci = oci.standby
  }

  compartment_id = var.compartment_id
  name_prefix     = "${var.project_name}-standby"
  role            = "standby"

  create_network     = var.create_networks
  existing_vcn_id    = var.standby_existing_vcn_id
  existing_subnet_id = var.standby_existing_subnet_id
  existing_nsg_ids   = var.standby_existing_nsg_ids
  vcn_cidr           = var.standby_vcn_cidr
  cache_subnet_cidr  = var.standby_cache_subnet_cidr
  vcn_dns_label      = var.standby_vcn_dns_label
  subnet_dns_label   = var.standby_subnet_dns_label
  application_cidrs  = var.standby_application_cidrs

  cluster_mode       = var.cluster_mode
  software_version   = var.software_version
  node_count         = var.node_count
  node_memory_in_gbs = var.node_memory_in_gbs
  shard_count        = var.shard_count

  create_cache_user          = var.create_cache_user
  cache_user_name            = var.cache_user_name
  cache_user_description     = "Application user for ${var.project_name} in ${var.standby_region}"
  cache_user_acl             = var.cache_user_acl
  cache_user_password_hashes = var.cache_user_password_hashes
  existing_cache_user_ocids  = var.standby_existing_cache_user_ocids

  freeform_tags = local.common_tags
}
