output "cluster_id" {
  value = oci_redis_redis_cluster.this.id
}

output "primary_fqdn" {
  value = oci_redis_redis_cluster.this.primary_fqdn
}

output "replicas_fqdn" {
  value = oci_redis_redis_cluster.this.replicas_fqdn
}

output "discovery_fqdn" {
  value = oci_redis_redis_cluster.this.discovery_fqdn
}

output "application_endpoint_fqdn" {
  value = var.cluster_mode == "SHARDED" ? oci_redis_redis_cluster.this.discovery_fqdn : oci_redis_redis_cluster.this.primary_fqdn
}

output "vcn_id" {
  value = local.vcn_id
}

output "subnet_id" {
  value = local.subnet_id
}

output "cache_nsg_id" {
  value = oci_core_network_security_group.cache.id
}

output "created_cache_user_id" {
  value = var.create_cache_user ? oci_redis_oci_cache_user.application[0].id : null
}
