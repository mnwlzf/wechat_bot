-- 图片识别补充：微信报文里同时带原图与缩略图两套 md5/密钥，
-- 本地 .dat 的文件名到底用哪一套并不固定，两套都存下来便于定位与解密回退。
ALTER TABLE message_log ADD COLUMN media_thumb_md5 varchar(64);
ALTER TABLE message_log ADD COLUMN media_thumb_aeskey varchar(64);

COMMENT ON COLUMN message_log.media_thumb_md5 IS '图片缩略图 md5（cdnthumbmd5），本地文件名可能用它';
COMMENT ON COLUMN message_log.media_thumb_aeskey IS '图片缩略图密钥（cdnthumbaeskey）';
