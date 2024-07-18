locals {
  rhcos_cidr_blocks = ["0.0.0.0/0"]
  fcos_cidr_blocks = [
        # FCOS Production Jenkins
        "38.145.60.3/32",
        # FCOS Staging Jenkins
        "38.145.60.4/32",
        # Fedora Bastion Hosts
        # bastion01.fedoraproject.org 
        "38.145.60.11/32",
        # bastion02.fedoraproject.org 
        "38.145.60.12/32",
    ]
}
resource "aws_security_group" "sg" {
  name        = "${local.project}-security-group"
  description = "Allow SSH inbound traffic only"
  vpc_id      = local.aws_vpc_id

  ingress {
    description = "SSH access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.distro == "fcos" ? local.fcos_cidr_blocks : local.rhcos_cidr_blocks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.project}-security-group"
  }
}
