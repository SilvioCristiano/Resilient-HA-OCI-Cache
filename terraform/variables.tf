variable "compartment_id" {
  description = "OCID do compartment onde redes, NSGs, usuários e clusters serão criados."
  type        = string

  validation {
    condition     = startswith(var.compartment_id, "ocid1.compartment.")
    error_message = "compartment_id deve ser um OCID de compartment."
  }
}

variable "project_name" {
  description = "Prefixo usado nos nomes e tags dos recursos."
  type        = string
  default     = "resilient-ha-oci-cache"

  validation {
    condition     = can(regex("^[a-zA-Z0-9._-]+$", var.project_name))
    error_message = "project_name aceita apenas letras, números, ponto, hífen e underscore."
  }
}

variable "oci_auth" {
  description = "Método de autenticação do provider OCI."
  type        = string
  default     = "APIKey"

  validation {
    condition     = contains(["APIKey", "SecurityToken", "InstancePrincipal", "ResourcePrincipal"], var.oci_auth)
    error_message = "oci_auth deve ser APIKey, SecurityToken, InstancePrincipal ou ResourcePrincipal."
  }
}

variable "oci_config_profile" {
  description = "Profile do ~/.oci/config usado com APIKey ou SecurityToken."
  type        = string
  default     = "DEFAULT"
}

variable "primary_region" {
  description = "Identificador da região OCI primária."
  type        = string
  default     = "sa-saopaulo-1"
}

variable "standby_region" {
  description = "Identificador da região OCI de DR."
  type        = string
  default     = "sa-vinhedo-1"
}

variable "create_networks" {
  description = "Cria VCN e subnet privada em cada região. Use false para redes existentes."
  type        = bool
  default     = true
}

variable "primary_vcn_cidr" {
  description = "CIDR da VCN primária criada pelo stack."
  type        = string
  default     = "10.10.0.0/16"
}

variable "primary_cache_subnet_cidr" {
  description = "CIDR da subnet privada do OCI Cache primário."
  type        = string
  default     = "10.10.20.0/24"
}

variable "standby_vcn_cidr" {
  description = "CIDR da VCN de DR criada pelo stack."
  type        = string
  default     = "10.20.0.0/16"
}

variable "standby_cache_subnet_cidr" {
  description = "CIDR da subnet privada do OCI Cache de DR."
  type        = string
  default     = "10.20.20.0/24"
}

variable "primary_vcn_dns_label" {
  type        = string
  description = "DNS label da VCN primária."
  default     = "hacachep"
}

variable "primary_subnet_dns_label" {
  type        = string
  description = "DNS label da subnet primária."
  default     = "cachep"
}

variable "standby_vcn_dns_label" {
  type        = string
  description = "DNS label da VCN de DR."
  default     = "hacachedr"
}

variable "standby_subnet_dns_label" {
  type        = string
  description = "DNS label da subnet de DR."
  default     = "cachedr"
}

variable "primary_application_cidrs" {
  description = "CIDRs autorizados a acessar o OCI Cache primário por TLS/6379."
  type        = set(string)
  default     = ["10.10.0.0/16", "10.20.0.0/16"]

  validation {
    condition = (
      length(var.primary_application_cidrs) > 0 &&
      alltrue([for cidr in var.primary_application_cidrs : can(cidrhost(cidr, 0))])
    )
    error_message = "Informe pelo menos um CIDR IPv4 válido para a região primária."
  }
}

variable "standby_application_cidrs" {
  description = "CIDRs autorizados a acessar o OCI Cache de DR por TLS/6379."
  type        = set(string)
  default     = ["10.10.0.0/16", "10.20.0.0/16"]

  validation {
    condition = (
      length(var.standby_application_cidrs) > 0 &&
      alltrue([for cidr in var.standby_application_cidrs : can(cidrhost(cidr, 0))])
    )
    error_message = "Informe pelo menos um CIDR IPv4 válido para a região de DR."
  }
}

variable "primary_existing_vcn_id" {
  description = "VCN primária existente; obrigatório quando create_networks=false."
  type        = string
  default     = null
  nullable    = true
}

variable "primary_existing_subnet_id" {
  description = "Subnet privada primária existente; obrigatório quando create_networks=false."
  type        = string
  default     = null
  nullable    = true
}

variable "standby_existing_vcn_id" {
  description = "VCN de DR existente; obrigatório quando create_networks=false."
  type        = string
  default     = null
  nullable    = true
}

variable "standby_existing_subnet_id" {
  description = "Subnet privada de DR existente; obrigatório quando create_networks=false."
  type        = string
  default     = null
  nullable    = true
}

variable "primary_existing_nsg_ids" {
  description = "NSGs adicionais associados ao cluster primário."
  type        = list(string)
  default     = []
}

variable "standby_existing_nsg_ids" {
  description = "NSGs adicionais associados ao cluster de DR."
  type        = list(string)
  default     = []
}

variable "cluster_mode" {
  description = "Modo aceito pela API OCI Cache: NONSHARDED ou SHARDED."
  type        = string
  default     = "NONSHARDED"

  validation {
    condition     = contains(["NONSHARDED", "SHARDED"], var.cluster_mode)
    error_message = "cluster_mode deve ser NONSHARDED ou SHARDED."
  }
}

variable "software_version" {
  description = "Versão do engine OCI Cache."
  type        = string
  default     = "VALKEY_8_1"

  validation {
    condition     = contains(["VALKEY_8_1", "VALKEY_7_2", "REDIS_7_0"], var.software_version)
    error_message = "Use VALKEY_8_1, VALKEY_7_2 ou REDIS_7_0."
  }
}

variable "node_count" {
  description = "Nós totais em NONSHARDED ou nós por shard em SHARDED."
  type        = number
  default     = 3

  validation {
    condition     = var.node_count >= 1 && var.node_count <= 5
    error_message = "node_count deve estar entre 1 e 5."
  }
}

variable "node_memory_in_gbs" {
  description = "Memória em GB de cada nó."
  type        = number
  default     = 2

  validation {
    condition     = var.node_memory_in_gbs >= 2
    error_message = "node_memory_in_gbs deve ser pelo menos 2."
  }
}

variable "shard_count" {
  description = "Quantidade de shards; usada somente em SHARDED."
  type        = number
  default     = 3

  validation {
    condition     = var.shard_count >= 3 && var.shard_count <= 99
    error_message = "shard_count deve estar entre 3 e 99."
  }
}

variable "create_cache_user" {
  description = "Cria um OCI Cache user com o mesmo nome e hashes nas duas regiões."
  type        = bool
  default     = false
}

variable "cache_user_name" {
  description = "Username usado pela aplicação."
  type        = string
  default     = "orders-app"
}

variable "cache_user_acl" {
  description = "ACL do usuário. Restringe chaves da demo e categorias necessárias."
  type        = string
  default     = "on ~resilient-ha-oci-cache:* ~{orders}:* +@read +@write +@connection +@scripting"
}

variable "cache_user_password_hashes" {
  description = "Hashes SHA-256 das senhas. O valor é sensível, mas permanece no state do Terraform."
  type        = list(string)
  default     = []
  sensitive   = true
}

variable "primary_existing_cache_user_ocids" {
  description = "OCI Cache users existentes a associar ao cluster primário."
  type        = list(string)
  default     = []
}

variable "standby_existing_cache_user_ocids" {
  description = "OCI Cache users existentes a associar ao cluster de DR."
  type        = list(string)
  default     = []
}

variable "freeform_tags" {
  description = "Tags livres adicionais."
  type        = map(string)
  default = {
    environment = "demo"
  }
}
