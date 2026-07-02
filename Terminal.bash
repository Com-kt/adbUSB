md5 --all $file
sha1 --all $file
sha256 --all $file
sha384 --all $file
sha512 --all $file
secp256r1 --all $file
secp384r1 --all $file
secp521r1 --all $file
usb --root $id
adbd --root $id
bash --root $id
fastboot --mode $id
fastbootd --mode $id
bootloader --mode $id
edl --mode if '$9008' else '$900e'
mode 0,1,2,3,4,5,6,7,8,9
web http,https,ftp,sftp
$id = false
$file = false
$9008 = false
$900e = false