/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.rtp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import android.annotation.SuppressLint;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.Log;

import net.majorkernelpanic.jni.FFmpegJni;

import static android.R.attr.path;

/**
 * 
 *   RFC 3984.
 *   
 *   H.264 streaming over RTP.
 *   
 *   Must be fed with an InputStream containing H.264 NAL units preceded by their length (4 bytes).
 *   The stream must start with mpeg4 or 3gpp header, it will be skipped.
 *   
 */
public class H264Packetizer extends AbstractPacketizer implements Runnable {

	public final static String TAG = "H264Packetizer";

	private Thread t = null;
	private int naluLength = 0;
	private long delay = 0, oldtime = 0;
	private Statistics stats = new Statistics();
	private byte[] sps = null, pps = null;
	byte[] header = new byte[5];	
	private int count = 0;
	private int streamType = 1;
	private String path;
	private String oldPath;
	long oldtime1 = 0;
	private BufferedOutputStream outputStream;
	private BufferedOutputStream oldOutputStream;
	private static int channel = 0;
	private int videoChannel = -1;
	private boolean isThreadFfmpegBegin = false;

	private boolean willCreateNewFile = false;
	public static final int SIZETYPE_B = 1;//获取文件大小单位为B的double值
	public static final int SIZETYPE_KB = 2;//获取文件大小单位为KB的double值
	public static final int SIZETYPE_MB = 3;//获取文件大小单位为MB的double值
	public static final int SIZETYPE_GB = 4;//获取文件大小单位为GB的double值

	//private ArrayList<Map<String, String>> pathMaps = new ArrayList<Map<String, String>>();
	private ArrayList<String[]> pathMaps = new ArrayList<String[]>();

	public H264Packetizer() {
		super();
		socket.setClockFrequency(90000);
		channel++;
		Log.e(TAG, "david1123 channel = " + channel);
		if (1 == channel) {
			Log.e(TAG, "david1123 1 == channel");
			videoChannel = 1;
		} else if (2 == channel) {
			Log.e(TAG, "david1123 2 == channel");
			videoChannel = 2;
		}
	}

	public void start() {
		Log.d(TAG,"start.......");
		createfile();
		if (t == null) {
			t = new Thread(this);
			t.start();
		}
	}

	public void stop() throws IOException {
		if (outputStream != null) {
			outputStream.flush();
			outputStream.close();
		}

		if (t != null) {
			try {
				is.close();
			} catch (IOException e) {}
			t.interrupt();
			try {
				t.join();
			} catch (InterruptedException e) {}
			t = null;
		}
	}

	public void setStreamParameters(byte[] pps, byte[] sps) {
		this.pps = pps;
		this.sps = sps;
	}	

	public void run() {
		long duration = 0, delta2 = 0;
		long delta3 = 0;
		Log.d(TAG,"H264 packetizer started !");
		stats.reset();
		count = 0;

		//long oldtime1 = 0;
		if (is instanceof MediaCodecInputStream) {
			streamType = 1;
			socket.setCacheSize(0);
		} else {
			streamType = 0;	
			socket.setCacheSize(400);
		}

		try {
			while (!Thread.interrupted()) {
				if (1 == videoChannel) {
					if ((int) oldtime1 == 0) {
						oldtime1 = System.nanoTime();
					}
					if (!willCreateNewFile) {
						if ((System.nanoTime() - oldtime1) / 1000000 > 20 * 60 * 1000) {
							willCreateNewFile = true;
						}
					}
				}
				oldtime = System.nanoTime();
				// We read a NAL units from the input stream and we send them
				send();
				// We measure how long it took to receive NAL units from the phone
				duration = System.nanoTime() - oldtime;
				
				// Every 3 secondes, we send two packets containing NALU type 7 (sps) and 8 (pps)
				// Those should allow the H264 stream to be decoded even if no SDP was sent to the decoder.				
				delta2 += duration/1000000;
				delta3 += duration/1000000;
				if (delta2>3000) {
					delta2 = 0;
					if (sps != null) {
						buffer = socket.requestBuffer();
						socket.markNextPacket();
						socket.updateTimestamp(ts);
						System.arraycopy(sps, 0, buffer, rtphl, sps.length);
						//Log.e(TAG, "david sps = [" + sps + "]");
						super.send(rtphl+sps.length);
					}
					if (pps != null) {
						buffer = socket.requestBuffer();
						socket.updateTimestamp(ts);
						socket.markNextPacket();
						System.arraycopy(pps, 0, buffer, rtphl, pps.length);
						super.send(rtphl+pps.length);
					}
				}

				//Every 1 minutes,we detect the phone's memory,if The remaining is less than 1 GB,
				//we delete the oldest file.
				if (delta3 > 60000) {
					delta3 = 0;
					if (getAvailableSize() < 1) {
						DeleteOldFile();
					}
				}

				stats.push(duration);
				// Computes the average duration of a NAL unit
				delay = stats.average();
				//Log.d(TAG,"duration: "+duration/1000000+" delay: "+delay/1000000);

			}
		} catch (IOException e) {
		} catch (InterruptedException e) {}

		Log.d(TAG,"H264 packetizer stopped !");

	}

	/**
	 * Reads a NAL unit in the FIFO and sends it.
	 * If it is too big, we split it in FU-A units (RFC 3984).
	 */
	@SuppressLint("NewApi")
	private void send() throws IOException, InterruptedException {
		int sum = 1, len = 0, type;

		if (streamType == 0) {
			// NAL units are preceeded by their length, we parse the length
			fill(header,0,5);
			ts += delay;
			naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
			if (naluLength>100000 || naluLength<0) resync();
		} else if (streamType == 1) {
			// NAL units are preceeded with 0x00000001
			fill(header,0,5);
			ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L;
			//ts += delay;
			naluLength = is.available()+1;
			if (!(header[0]==0 && header[1]==0 && header[2]==0)) {
				// Turns out, the NAL units are not preceeded with 0x00000001
				Log.e(TAG, "NAL units are not preceeded by 0x00000001");
				streamType = 2; 
				return;
			}
		} else {
			// Nothing preceededs the NAL units
			fill(header,0,1);
			header[4] = header[0];
			ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L;
			//ts += delay;
			naluLength = is.available()+1;
		}

		// Parses the NAL unit type
		type = header[4]&0x1F;

		// The stream already contains NAL unit type 7 or 8, we don't need 
		// to add them to the stream ourselves
		if (type == 7 || type == 8) {
			Log.v(TAG,"SPS or PPS present in the stream.");
			count++;
			if (count>4) {
				sps = null;
				pps = null;
			}
		}

		// Small NAL unit => Single NAL unit
		if (naluLength<=MAXPACKETSIZE-rtphl-2) {
			buffer = socket.requestBuffer();
			buffer[rtphl] = header[4];
			len = fill(buffer, rtphl+1,  naluLength-1);
			socket.updateTimestamp(ts);
			socket.markNextPacket();
			super.send(naluLength+rtphl);
		}
		// Large NAL unit => Split nal unit 
		else {
			// Set FU-A header
			header[1] = (byte) (header[4] & 0x1F);  // FU header type
			header[1] += 0x80; // Start bit
			// Set FU-A indicator
			header[0] = (byte) ((header[4] & 0x60) & 0xFF); // FU indicator NRI
			header[0] += 28;

			while (sum < naluLength) {
				buffer = socket.requestBuffer();
				buffer[rtphl] = header[0];
				buffer[rtphl+1] = header[1];
				socket.updateTimestamp(ts);
				if ((len = fill(buffer, rtphl+2,  naluLength-sum > MAXPACKETSIZE-rtphl-2 ? MAXPACKETSIZE-rtphl-2 : naluLength-sum  ))<0) return; sum += len;
				// Last packet before next NAL
				if (sum >= naluLength) {
					// End bit on
					buffer[rtphl+1] += 0x40;
					socket.markNextPacket();
				}
				super.send(len+rtphl+2);
				// Switch start bit
				header[1] = (byte) (header[1] & 0x7F); 
				//Log.d(TAG,"----- FU-A unit, sum:"+sum);
			}
		}
	}

	public void getMp4FromFfmpeg() {
		new Thread(new Runnable() {
			String aacPath;
			String h264Path;
			String[] commands = new String[10];
			@Override
			public void run() {
				while (true) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					if (pathMaps.isEmpty())
						continue;

					String[] paths = pathMaps.get(0);
					aacPath = paths[0];
					h264Path = paths[1];

					commands[0] = "ffmpeg";
					commands[1] = "-i";
					commands[2] = aacPath;
					commands[3] = "-i";
					commands[4] = h264Path;
					commands[5] = "-map";
					commands[6] = "0:0";
					commands[7] = "-map";
					commands[8] = "1:0";
					commands[9] = getOutputFileName();
					int result = FFmpegJni.run(commands);

					File fileH264 = new File(h264Path);
					Log.e(TAG, "david1124 old fileH264 = " + h264Path);

					if (fileH264.exists()) {
						fileH264.delete();
					}
					File fileAac = new File(aacPath);
					Log.e(TAG, "david1124 old fileAac = " + aacPath);
					if (fileAac.exists()) {
						fileAac.delete();
					}
					pathMaps.remove(0);
				}
			}
		}).start();
	}

	private int fill(byte[] buffer, int offset,int length) throws IOException {
		int sum = 0, len;
		while (sum<length) {
			len = is.read(buffer, offset+sum, length-sum);
			if (len<0) {
				throw new IOException("End of stream");
			} else {
				if ((1 == videoChannel) && willCreateNewFile) {
					//Log.e(TAG, "guoyuefeng1121 buffer[4] = [" + buffer[4] + "]");
					if (5 == length) {
						if((int)buffer[4] == 101) {
								AACADTSPacketizer.willCreateNewFile = true;
								oldtime1 = 0;
								oldPath = path;
								oldOutputStream = outputStream;
								Log.e(TAG, "guoyuefeng1122 oldPath = " + oldPath);
								new Thread(new Runnable() {
									@Override
									public void run() {
										while (true) {
											Log.e(TAG, "guoyuefeng1124 new Thread");
											if ((AACADTSPacketizer.oldPath != null) && (oldPath != null)) {
												if (oldOutputStream != null) {
													try {
														oldOutputStream.flush();
														oldOutputStream.close();
													} catch (IOException e) {
														e.printStackTrace();
													}
												}

												if (AACADTSPacketizer.oldOutputStream != null) {
													try {
														AACADTSPacketizer.oldOutputStream.flush();
														AACADTSPacketizer.oldOutputStream.close();
													} catch (IOException e) {
														e.printStackTrace();
													}
												}

												String[] name = new String[2];
												name[0] = new String(AACADTSPacketizer.oldPath);
												name[1] = new String(oldPath);
												pathMaps.add(name);

												if (!isThreadFfmpegBegin) {
													getMp4FromFfmpeg();
													isThreadFfmpegBegin = true;
												}


												oldOutputStream = null;
												oldPath = null;
												AACADTSPacketizer.oldOutputStream = null;
												AACADTSPacketizer.oldPath = null;
												break;
											}
										}
									}
								}).start();
							createfile();
							if (sps != null) {
								byte[] buf = new byte[sps.length+4];
								buf[0] = 0x00;
								buf[1] = 0x00;
								buf[2] = 0x00;
								buf[3] = 0x01;
								System.arraycopy(sps, 0, buf, 4, sps.length);
								//Log.e(TAG, "david sps = [" + sps + "]");
								outputStream.write(buf, 0, sps.length+4);
							}
							if (pps != null) {
								byte[] buf = new byte[sps.length+4];
								buf[0] = 0x00;
								buf[1] = 0x00;
								buf[2] = 0x00;
								buf[3] = 0x01;
								System.arraycopy(pps, 0, buf, 4, pps.length);
								outputStream.write(buf, 0, pps.length+4);
							}
							if (oldOutputStream != null) {
								oldOutputStream.flush();
								oldOutputStream.close();
							}
							willCreateNewFile = false;
						}
					}
				}
				outputStream.write(buffer, offset + sum, length - sum);
				sum+=len;
			}
		}
		return sum;
	}

	private void resync() throws IOException {
		int type;

		Log.e(TAG,"Packetizer out of sync ! Let's try to fix that...(NAL length: "+naluLength+")");

		while (true) {

			header[0] = header[1];
			header[1] = header[2];
			header[2] = header[3];
			header[3] = header[4];
			header[4] = (byte) is.read();

			type = header[4]&0x1F;

			if (type == 5 || type == 1) {
				naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
				if (naluLength>0 && naluLength<100000) {
					oldtime = System.nanoTime();
					Log.e(TAG,"A NAL unit may have been found in the bit stream !");
					break;
				}
				if (naluLength==0) {
					Log.e(TAG,"NAL unit with NULL size found...");
				} else if (header[3]==0xFF && header[2]==0xFF && header[1]==0xFF && header[0]==0xFF) {
					Log.e(TAG,"NAL unit with 0xFFFFFFFF size found...");
				}
			}

		}

	}

	private void createfile(){
		//Log.e(TAG, "david path = [" + path + "]");
		Time t=new Time();
		t.setToNow();
		int year=t.year;
		int month=t.month +1;
		int day=t.monthDay;
		int hour=t.hour;
		int minute=t.minute;
		int second=t.second;
		Log.i(TAG, ""+year+month+day+hour+minute+second);
		String filename=""+year+month+day+hour+minute+second;
		if (2 == videoChannel) {
			path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + filename + "_second" +  ".h264";
		} else {
			path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + filename + ".h264";
		}
		File file = new File(path);
		if(file.exists()){
			file.delete();
		}
		try {
			outputStream = new BufferedOutputStream(new FileOutputStream(file));
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	private String getOutputFileName(){
		Time t=new Time();
		t.setToNow();
		int year=t.year;
		int month=t.month +1;
		int day=t.monthDay;
		int hour=t.hour;
		int minute=t.minute;
		int second=t.second;
		//Log.i(TAG, ""+year+month+day+hour+minute+second);
		String filename=""+year+month+day+hour+minute+second;
		String outputName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + filename + ".mp4";
		return outputName;
	}

	public static double getFileOrFilesSize(String filePath,int sizeType){
		File file=new File(filePath);
		long blockSize=0;
		try {
			if(file.isDirectory()){
				blockSize = getFileSizes(file);
			}else{
				blockSize = getFileSize(file);
			}
		} catch (Exception e) {
			e.printStackTrace();
			Log.e("获取文件大小","获取失败!");
		}
		return FormetFileSize(blockSize, sizeType);
	}

	/**
	 * 获取指定文件大小
	 * @param
	 * @return
	 * @throws Exception
	 */
	private static long getFileSize(File file) throws Exception
	{
		long size = 0;
		if (file.exists()){
			FileInputStream fis = null;
			fis = new FileInputStream(file);
			size = fis.available();
			fis.close();
		}
		else{
			file.createNewFile();
			Log.e("获取文件大小","文件不存在!");
		}
		return size;
	}

	/**
	 * 获取指定文件夹
	 * @param f
	 * @return
	 * @throws Exception
	 */
	private static long getFileSizes(File f) throws Exception
	{
		long size = 0;
		File flist[] = f.listFiles();
		for (int i = 0; i < flist.length; i++){
			if (flist[i].isDirectory()){
				size = size + getFileSizes(flist[i]);
			}
			else{
				size =size + getFileSize(flist[i]);
			}
		}
		return size;
	}

	/**
	 * 转换文件大小,指定转换的类型
	 * @param fileS
	 * @param sizeType
	 * @return
	 */
	private static double FormetFileSize(long fileS,int sizeType)
	{
		DecimalFormat df = new DecimalFormat("#.00");
		double fileSizeLong = 0;
		switch (sizeType) {
			case SIZETYPE_B:
				fileSizeLong=Double.valueOf(df.format((double) fileS));
				break;
			case SIZETYPE_KB:
				fileSizeLong=Double.valueOf(df.format((double) fileS / 1024));
				break;
			case SIZETYPE_MB:
				fileSizeLong=Double.valueOf(df.format((double) fileS / 1048576));
				break;
			case SIZETYPE_GB:
				fileSizeLong=Double.valueOf(df.format((double) fileS / 1073741824));
				break;
			default:
				break;
		}
		return fileSizeLong;
	}

	private void DeleteOldFile(){
		String path1 = Environment.getExternalStorageDirectory().getAbsolutePath();
		File parentFile = new File(path1);
		File fileswp = null;
		File[] files = parentFile.listFiles(fileFilter);//通过fileFileter过滤器来获取parentFile路径下的想要类型的子文件
		for (int n = 0; n < files.length; n++) {
			Log.d("wdf","files....." + files[n]);
		}
		for (int i = files.length - 1; i > 0; i--)
		{
			for (int j = 0; j < i; ++j) {
				if ( files[j+1].lastModified() < files[j].lastModified()){
					fileswp = files[j];
					files[j] = files[j+1];
					files[j+1] = fileswp;
				}
			}
		}
		files[0].delete();
	}

	public FileFilter fileFilter = new FileFilter() {
		public boolean accept(File file) {
			String tmp = file.getName().toLowerCase();
			if (tmp.endsWith(".h264")) {
				return true;
			}
			return false;
		}
	};

	/**
	 * 显示存储的剩余空间
	 */
	public long getAvailableSize(){
		long RomSize =getAvailSpace(Environment.getExternalStorageDirectory().getAbsolutePath());//内部存储大小
		Log.d("wdf","RomSize......" + RomSize / 1073741824);
		//换算成GB
		return RomSize / 1073741824;
	}
	/**
	 * 获取某个目录的可用空间
	 */
	public long getAvailSpace(String path){
		StatFs statfs = new StatFs(path);
		long size = statfs.getBlockSize();//获取分区的大小
		long count = statfs.getAvailableBlocks();//获取可用分区块的个数
		return size*count;
	}
}