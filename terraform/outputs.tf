output "primary_cluster_id" {
  description = "OCID do OCI Cache primário."
  value       = module.primary.cluster_id
}

output "standby_cluster_id" {
  description = "OCID do OCI Cache de DR."
  value       = module.standby.cluster_id
}

output "primary_endpoint_fqdn" {
  description = "FQDN para escrita/discovery do cluster primário."
  value       = module.primary.application_endpoint_fqdn
}

output "standby_endpoint_fqdn" {
  description = "FQDN para escrita/discovery do cluster de DR."
  value       = module.standby.application_endpoint_fqdn
}

output "primary_replicas_fqdn" {
  description = "FQDN de réplicas; disponível somente em NONSHARDED."
  value       = module.primary.replicas_fqdn
}

output "standby_replicas_fqdn" {
  description = "FQDN de réplicas; disponível somente em NONSHARDED."
  value       = module.standby.replicas_fqdn
}

output "primary_vcn_id" {
  value = module.primary.vcn_id
}

output "standby_vcn_id" {
  value = module.standby.vcn_id
}

output "primary_cache_subnet_id" {
  value = module.primary.subnet_id
}

output "standby_cache_subnet_id" {
  value = module.standby.subnet_id
}

output "primary_cache_nsg_id" {
  value = module.primary.cache_nsg_id
}

output "standby_cache_nsg_id" {
  value = module.standby.cache_nsg_id
}

output "cache_user_name" {
  description = "Username configurado para a aplicação. A senha em texto puro nunca é produzida pelo Terraform."
  value       = var.cache_user_name
}

output "spring_boot_environment" {
  description = "Variáveis não secretas que conectam a aplicação aos clusters."
  value = {
    OCI_PRIMARY_REGION                 = var.primary_region
    OCI_STANDBY_REGION                 = var.standby_region
    OCI_CACHE_PRIMARY_FQDN             = module.primary.application_endpoint_fqdn
    OCI_CACHE_STANDBY_FQDN             = module.standby.application_endpoint_fqdn
    OCI_CACHE_PRIMARY_REPLICAS_FQDN    = module.primary.replicas_fqdn
    OCI_CACHE_STANDBY_REPLICAS_FQDN    = module.standby.replicas_fqdn
    OCI_CACHE_PRIMARY_OCID             = module.primary.cluster_id
    OCI_CACHE_STANDBY_OCID             = module.standby.cluster_id
    OCI_CACHE_MODE                     = var.cluster_mode == "SHARDED" ? "SHARDED" : "NON_SHARDED"
    OCI_COMPARTMENT_OCID               = var.compartment_id
    OCI_STANDBY_SUBNET_OCID            = module.standby.subnet_id
    OCI_STANDBY_NSG_OCIDS              = module.standby.cache_nsg_id
    OCI_CACHE_USERNAME                 = var.cache_user_name
    OCI_CACHE_PROVISIONING_ENABLED     = "false"
  }
}

output "spring_boot_dotenv" {
  description = "Arquivo .env não secreto. Substitua OCI_CACHE_PASSWORD antes de iniciar a aplicação."
  value = <<-EOT
    OCI_PRIMARY_REGION=${var.primary_region}
    OCI_STANDBY_REGION=${var.standby_region}
    OCI_CACHE_PRIMARY_FQDN=${module.primary.application_endpoint_fqdn}
    OCI_CACHE_STANDBY_FQDN=${module.standby.application_endpoint_fqdn}
    OCI_CACHE_PRIMARY_REPLICAS_FQDN=${module.primary.replicas_fqdn != null ? module.primary.replicas_fqdn : ""}
    OCI_CACHE_STANDBY_REPLICAS_FQDN=${module.standby.replicas_fqdn != null ? module.standby.replicas_fqdn : ""}
    OCI_CACHE_PRIMARY_OCID=${module.primary.cluster_id}
    OCI_CACHE_STANDBY_OCID=${module.standby.cluster_id}
    OCI_CACHE_MODE=${var.cluster_mode == "SHARDED" ? "SHARDED" : "NON_SHARDED"}
    OCI_CACHE_USERNAME=${var.cache_user_name}
    OCI_CACHE_PASSWORD=CHANGE_ME
    OCI_COMPARTMENT_OCID=${var.compartment_id}
    OCI_STANDBY_SUBNET_OCID=${module.standby.subnet_id}
    OCI_STANDBY_NSG_OCIDS=${module.standby.cache_nsg_id}
    OCI_CACHE_PROVISIONING_ENABLED=false
  EOT
}
