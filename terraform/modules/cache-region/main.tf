locals {
  vcn_id = var.create_network ? oci_core_vcn.this[0].id : var.existing_vcn_id
  subnet_id = var.create_network ? oci_core_subnet.cache[0].id : var.existing_subnet_id
  cache_user_ocids = concat(
    var.existing_cache_user_ocids,
    var.create_cache_user ? [oci_redis_oci_cache_user.application[0].id] : [],
  )
}

resource "oci_core_vcn" "this" {
  count = var.create_network ? 1 : 0

  compartment_id = var.compartment_id
  cidr_blocks     = [var.vcn_cidr]
  display_name    = "${var.name_prefix}-vcn"
  dns_label       = var.vcn_dns_label
  freeform_tags   = var.freeform_tags
}

resource "oci_core_subnet" "cache" {
  count = var.create_network ? 1 : 0

  compartment_id             = var.compartment_id
  vcn_id                     = oci_core_vcn.this[0].id
  cidr_block                 = var.cache_subnet_cidr
  display_name               = "${var.name_prefix}-cache-subnet"
  dns_label                  = var.subnet_dns_label
  prohibit_public_ip_on_vnic = true
  freeform_tags              = var.freeform_tags
}

resource "oci_core_network_security_group" "cache" {
  compartment_id = var.compartment_id
  vcn_id         = local.vcn_id
  display_name   = "${var.name_prefix}-cache-nsg"
  freeform_tags  = var.freeform_tags

  lifecycle {
    precondition {
      condition     = var.create_network || (var.existing_vcn_id != null && var.existing_subnet_id != null)
      error_message = "existing_vcn_id e existing_subnet_id são obrigatórios quando create_network=false."
    }
  }
}

resource "oci_core_network_security_group_security_rule" "cache_ingress" {
  for_each = var.application_cidrs

  network_security_group_id = oci_core_network_security_group.cache.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = each.value
  source_type               = "CIDR_BLOCK"
  stateless                 = false
  description               = "TLS OCI Cache from application CIDR ${each.value}"

  tcp_options {
    destination_port_range {
      min = 6379
      max = 6379
    }
  }
}

resource "oci_redis_redis_cluster" "this" {
  compartment_id     = var.compartment_id
  display_name       = "${var.name_prefix}-cluster"
  cluster_mode       = var.cluster_mode
  software_version   = var.software_version
  node_count         = var.node_count
  node_memory_in_gbs = var.node_memory_in_gbs
  shard_count        = var.cluster_mode == "SHARDED" ? var.shard_count : null
  subnet_id          = local.subnet_id
  nsg_ids            = concat([oci_core_network_security_group.cache.id], var.existing_nsg_ids)
  freeform_tags      = merge(var.freeform_tags, { role = var.role })

  timeouts {
    create = "30m"
    update = "30m"
    delete = "30m"
  }
}

resource "oci_redis_oci_cache_user" "application" {
  count = var.create_cache_user ? 1 : 0

  compartment_id = var.compartment_id
  name           = var.cache_user_name
  description    = var.cache_user_description
  acl_string     = var.cache_user_acl
  status         = "ON"
  freeform_tags  = var.freeform_tags

  authentication_mode {
    authentication_type = "PASSWORD"
    hashed_passwords     = var.cache_user_password_hashes
  }

  lifecycle {
    precondition {
      condition     = nonsensitive(length(var.cache_user_password_hashes)) > 0
      error_message = "cache_user_password_hashes deve conter pelo menos um hash SHA-256 quando create_cache_user=true."
    }
  }
}

resource "oci_redis_redis_cluster_attach_oci_cache_user" "application" {
  count = length(local.cache_user_ocids) > 0 ? 1 : 0

  redis_cluster_id = oci_redis_redis_cluster.this.id
  oci_cache_users  = local.cache_user_ocids
}
